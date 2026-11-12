import json

from tg2oto.json2oto.oto_dedup import dedup_oto
from tg2oto.oto.oto_fmt import convert_line
import re


#将json转换为oto
#文件名=别名,左边界,固定,右边界（负值）,预发声,交叉
#autio_name=phone_name,left,fixed,right（负值）,Prevoice,cross
#CV(V),VC(VV)(VR),-CV（-V）


# UTAU 规范里 cutoff 负值从 offset 起算，但 OpenUtau 实测按「从文件末尾」解释。
# 两种口径结果相反：同一句 a て，前者播放到 syllable 末尾，后者会把母音整段切掉。
# 这里做成开关，由 runner 按界面设置赋值。
CUTOFF_FROM_END = False


def _cut(right, xmax_ms, total_ms):
    if CUTOFF_FROM_END and total_ms > 0:
        return max(0.0, total_ms - xmax_ms)
    return right

def presamp_read(presamps_path):
    CV_V = {}
    CV_C = {}
    V_V = []
    with open(presamps_path, 'r',encoding='utf-8') as file:
        ini_text = file.read()
        # 提取 [VOWEL] 部分
        # 提取 [VOWEL] 部分
        vowel_match = re.search(r'\[VOWEL\](.*?)\[', ini_text, re.DOTALL)
        vowels = vowel_match.group(1).strip()
        # print(vowels)
        # 提取 [CONSONANT] 部分
        consonant_match = re.search(r'\[CONSONANT\](.*?)\[', ini_text, re.DOTALL)
        # 只有 [VOWEL] 的 presamp.ini（项目自带的「樗儿式中文VCV」、英文 ARPASing 表）
        # 之前会在 group(1) 处抛 AttributeError 直接中断生成，这里给空表兜底。
        consonants = consonant_match.group(1).strip() if consonant_match else ''
        # print(consonants)
    for vowel in vowels.split('\n'):
        if vowel == '':
            continue
        V0=(vowel.split('=')[0])
        for vowel in vowel.split('=')[2].split(','):
            CV_V[vowel]=V0
    for consonant in consonants.split('\n'):
        if consonant == '':
            continue
        C0=(consonant.split('=')[0])
        for consonant in consonant.split('=')[1].split(','):
            CV_C[consonant]=C0
    V_V = [key for key in CV_V if key not in CV_C]
    print(f"CV_V:{CV_V}\nCV_C:{CV_C}\n,V_V:{V_V}")
    return CV_V,V_V

def json2cvoto(cv_data,sum,ignore):
    oto = []
    for audio_file, data in cv_data.items():
        autio_name = audio_file[:-4] if str(audio_file).lower().endswith(".wav") else audio_file
        phones = data.get('phones', {})
        long = data.get('long') or data.get('wav_long') or []
        total_ms = float(long[1]) * 1000 if len(long) >= 2 else 0.0
        if not phones:
            continue

        sorted_phones = sorted(phones.items(), key=lambda x: int(x[0]))
        # 剔除夹在音节之间的边界音素（R/SP/AP）：连单录音的音节之间会有静音段，
        # 对齐偶尔把它单独标成 R/SP，会让「前音节→后音节」的相邻对被切断，
        # 使后音节被误判成句首。首尾的边界保留，供句首/末尾分支判断。
        _BND = ("R", "SP", "AP", "-", "sil")
        _n = len(sorted_phones)
        sorted_phones = [
            (k, v) for i, (k, v) in enumerate(sorted_phones)
            if not (1 <= i <= _n - 2 and str(v.get("text")) in _BND)
        ]
        #保证最后一个音符不会被忽略
        keyend, contend = sorted_phones[-1]
        if contend['text'] not in ignore:
            phone_name = contend['text']
            # autio_name=phone_name,left,fixed,right（负值）,Prevoice,cross
            left = float(contend['xmin']) * 1000 / sum[0]

            Prevoice = (float(contend['middle']) - float(contend['xmin'])) * 1000
            if float(sum[3]) != 1:
                Prevoice2 = Prevoice / sum[3]
                left += Prevoice - Prevoice2
                Prevoice = Prevoice2
            # 右线占比
            right = (float(contend['xmax']) - float(contend['middle'])) * 1000 / sum[2] + Prevoice
            # 固定的占比
            if sum[1] == 0:
                fixed = Prevoice
            else:
                fixed = Prevoice + (right - Prevoice) / sum[1]
            cross = float(Prevoice) / sum[4]

            oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(contend["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")

        # 句首兜底：若第一条直接就是音节（前面没有 R/SP 占位），也补一条「- 音素」。
        # 少了它，这个 wav 在 OpenUtau 里句首只能落到歌词显示，表现为「- て / - す 缺失」，
        # 且只发生在首音素不是边界的那部分文件上。
        if len(sorted_phones) > 1:
            _f = sorted_phones[0][1]
            if _f.get('text') not in ignore and str(_f.get('text')) not in _BND:
                phone_name = '- ' + str(_f['text'])
                left = float(_f['xmin']) * 1000 / sum[0]
                Prevoice = (float(_f['middle']) - float(_f['xmin'])) * 1000
                if sum[3] != 1:
                    Prevoice2 = Prevoice / sum[3]
                    left += Prevoice - Prevoice2
                    Prevoice = Prevoice2
                right = (float(_f['xmax']) - float(_f['middle'])) * 1000 / sum[2] + Prevoice
                if sum[1] == 0:
                    fixed = Prevoice
                else:
                    fixed = Prevoice + (right - Prevoice) / sum[1]
                cross = float(Prevoice) / sum[4]
                oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(_f['xmax']) * 1000, total_ms)},{Prevoice},{cross}\n")

        i = 0
        while i < len(sorted_phones)-1:
            key, cont = sorted_phones[i]
            # -CV规则。边界音素即使没写进 ignore 也应按边界处理，
            # 否则首音素是 SP 而 ignore 里只有 AP/R 时，整句都生成不出「- 音素」。
            if str(cont['text']) in _BND or cont['text'] in ignore:
                key1, cont2 = sorted_phones[i + 1]
                phone_name = cont2['text']
                # autio_name=phone_name,left,fixed,right（负值）,Prevoice,cross
                left = float(cont2['xmin'])*1000/sum[0]

                Prevoice = (float(cont2['middle']) - float(cont2['xmin'])) * 1000
                if float(sum[3]) != 1:
                    Prevoice2 = Prevoice / sum[3]
                    left += Prevoice - Prevoice2
                    Prevoice = Prevoice2
                #右线占比
                right = (float(cont2['xmax'])-float(cont2['middle']))*1000/sum[2] + Prevoice
                # 固定的占比
                if sum[1] ==0:
                    fixed = Prevoice
                else:
                    fixed = Prevoice+(right-Prevoice)/sum[1]
                cross = float(Prevoice)/sum[4]
                i+=2
                oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(cont2["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")
                continue
            #CV规则
            phone_name =cont['text']
            # autio_name=phone_name,left,fixed,right（负值）,Prevoice,cross
            left = float(cont['xmin']) * 1000 / sum[0]

            Prevoice = (float(cont['middle'])- float(cont['xmin'])) * 1000 / sum[3]
            # 右线占比
            right = (float(cont['xmax']) - float(cont['middle'])) * 1000 / sum[2] + Prevoice
            # 固定的占比
            if sum[1] == 0:
                fixed = Prevoice
            else:
                fixed = Prevoice+(right-Prevoice)/sum[1]
            cross = float(Prevoice) / sum[4]
            i += 1

            oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(cont["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")
            continue

    return oto


def json2vcoto(vc_data,C_V,vc_sum,vv_sum,ignore):
    oto = []
    for audio_file, data in vc_data.items():
        autio_name = audio_file[:-4] if str(audio_file).lower().endswith(".wav") else audio_file
        phones = data.get('phones', {})
        long = data.get('long') or data.get('wav_long') or []
        total_ms = float(long[1]) * 1000 if len(long) >= 2 else 0.0
        long = data.get('long', [])
        if not phones:
            continue

        sorted_phones = sorted(phones.items(), key=lambda x: int(x[0]))
        # 剔除夹在音节之间的边界音素（R/SP/AP）：连单录音的音节之间会有静音段，
        # 对齐偶尔把它单独标成 R/SP，会让「前音节→后音节」的相邻对被切断，
        # 使后音节被误判成句首。首尾的边界保留，供句首/末尾分支判断。
        _BND = ("R", "SP", "AP", "-", "sil")
        _n = len(sorted_phones)
        sorted_phones = [
            (k, v) for i, (k, v) in enumerate(sorted_phones)
            if not (1 <= i <= _n - 2 and str(v.get("text")) in _BND)
        ]
        i = 0
        for key, item in sorted_phones:
            if str(item['text']) in _BND or item['text'] in ignore:
                # 修改 text 为 R
                item['text'] = 'R'

        while i < len(sorted_phones) - 1:
            key, cont = sorted_phones[i]
            key1, cont1 = sorted_phones[i + 1]
            # print(cont['text'], cont1['text'], cont2['text'])
            # 0V 1C
            # CC规则
            # 后续删除此功能
            if cont['text'] in C_V:
                phone_name = C_V[cont['text']] + ' ' + 'R'
                # autio_name=phone_name,left,fixed,right（负值）,Prevoice,cross
                left = float(cont["middle"]) * 1000 + ((float(cont['xmax']) - float(cont['middle'])) * 1000 / vc_sum[0])
                # 右线占比
                Prevoice = float(cont['xmax']) * 1000 - left / vc_sum[3]
                right = Prevoice+50
                # 固定的占比
                if vc_sum[1] == 0:
                    fixed = Prevoice
                else:
                    fixed = Prevoice + (Prevoice-right) * 1000 / vc_sum[1]
                cross = (float(cont['xmax']) - float(cont['middle'])) * 1000 / vc_sum[4]
                # i += 1
                # print(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(cont["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")
                oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(cont["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")

                # CV / CVV 的官方 vv_sum 是 0,0,0,0,0（这两种录音方案不产 VV 条目），
                # 这里必须跳过，否则会除零崩溃。
                if not all(vv_sum):
                    i += 1
                    continue
                phone_name = '_'+C_V[cont['text']]
                left = float(cont["middle"]) * 1000 + ((float(cont['xmax']) - float(cont['middle'])) * 1000 / vv_sum[0])
                # 右线占比
                right = (float(cont['xmax']) * 1000 - left)/vv_sum[2]
                Prevoice = right / vv_sum[3]
                # 固定的占比
                fixed = (right - Prevoice) /vv_sum[1] +Prevoice
                cross = right / vv_sum[4]
                i += 1
                # print(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(cont["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")
                oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(cont["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")
            else:
                i+=1
                continue
    return oto

def v_cross(oto,cross_sum,V_V):
    oto2 = []
    for line in oto:
        autio_name, rest = line.split('=')
        rest = rest.strip()
        rest = rest.split(',')
        if rest[0] in V_V:
            cross = 20
            oto2.append(f"{autio_name}={rest[0]},{rest[1]},{rest[2]},{rest[3]},{rest[4]},{cross}\n")
        else:
            oto2.append(line)
    return oto2



def run(presamp_path,word_phone_json,wav_path,cv_sum,vc_sum,vv_sum,ignore):
    ignore = ignore.split(',')
    C_V,V_V = presamp_read(presamp_path)
    print(C_V)
    # with open(utau_phone_json, 'r', encoding='utf-8') as f:
    #     vc_data = json.load(f)
    with open(word_phone_json, 'r', encoding='utf-8') as f:
        cv_data = json.load(f)
    oto = dedup_oto(json2cvoto(cv_data,cv_sum,ignore))
    oto = v_cross(oto,vv_sum[4],V_V)
    # print(oto)
    with open(wav_path+'/cv_oto.ini', 'w', encoding='utf-8') as f:
        for i in oto:
            f.write(convert_line(i))
        print('cv_oto.ini生成成功')
    oto = dedup_oto(json2vcoto(cv_data,C_V, vc_sum,vv_sum,ignore))
    # print(oto)
    with open(wav_path+'/vc_oto.ini', 'w', encoding='utf-8') as f:
        for i in oto:
            f.write(convert_line(i))
        print('vc_oto.ini生成成功')


if __name__ == '__main__':
    # presamp_path = 'risku中文CVVCpresamp.ini'
    # utau_phone = 'G:/编程/utau自动标注/F3/TextGrid/json/utau_phone.json'
    # word_phone_json = 'G:/编程/utau自动标注/F3/TextGrid/json/word_phone.json'
    # wav_path = 'G:/编程/utau自动标注/F3'

    presamp_path = '../presamp/jp-hira-presamp.ini'
    wav_path = r'E:\OpenUtau\Singers\LXYM4_openutau\C5'
    utau_phone = wav_path + '/TextGrid/json/utau_phone.json'
    word_phone_json = wav_path + '/TextGrid/json/word_phone.json'


    #-CV和CV规则：左线占比,固定的占比,右线占比,预发声不变,交叉占比
    cv_sum = [1,3,1.5,1,2,3]
    #VC和VV规则：左线占比,固定的占比,右线占比,预发声不变,交叉占比,VV固定占比
    vc_sum=[3,0,2,1,2,3]
    vv_sum=[3,0,2,1,2,3]
    ignore = 'R,SP,AP'
    run(presamp_path,word_phone_json,wav_path,cv_sum,vc_sum,vv_sum,ignore)
