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

# 官方公式里句首「- X」的 cutoff 只算到本音节右半部，播放区间够不到第一条 VCV 条目，
# 两者之间会留空档（实测 143/202 个文件，10~170ms）。开启后把句首 cutoff 延伸到
# 下一条的起点附近，消除空档；但这是官方算法之外的推导，默认关，按需打开。
EXTEND_INITIAL_CUTOFF = False

# 句首「- X」的 offset 前探量（毫秒）。对齐常把句首音节的辅音起点判给了前面的
# R/SP 段，offset 落在辅音之后，合成时辅音被切掉、只剩元音。设成 20~50 可把这截
# 辅音收回；offset 前移多少，preutterance 就补多少，音头位置不变。默认 0 = 官方行为。
INITIAL_OFFSET_PREFETCH = 0.0


def _cut(right, xmax_ms, total_ms):
    if CUTOFF_FROM_END and total_ms > 0:
        return max(0.0, total_ms - xmax_ms)
    return right

def presamp_read(presamps_path):
    V = {}
    with open(presamps_path, 'r',encoding='utf-8') as file:
        ini_text = file.read()
        # 提取 [VOWEL] 部分
        # 提取 [VOWEL] 部分
        vowel_match = re.search(r'\[VOWEL\](.*?)\[', ini_text, re.DOTALL)
        vowels = vowel_match.group(1).strip()
        # print(vowels)
        # 提取 [CONSONANT] 部分
        # consonant_match = re.search(r'\[CONSONANT\](.*?)\[', ini_text, re.DOTALL)
        # consonants = consonant_match.group(1).strip()
        # print(consonants)
    for vowel in vowels.split('\n'):
        if vowel == '':
            continue
        v = vowel.split('=')[0]
        for v0 in vowel.split('=')[2].split(','):
            V[v0]=v
    # print(V)
    return V

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
        _BND = ("R", "SP", "AP", "-", "sil")
        # 句首兜底：首音素直接是音节（没有 R/SP 占位）时补一条「- 音素」，
        # 与 VCV 部分保持同一口径，避免这半个 oto 缺句首条目。
        if len(sorted_phones) > 1:
            _f0 = sorted_phones[0][1]
            if str(_f0.get('text')) not in _BND and _f0.get('text') not in ignore:
                _pname = '- ' + str(_f0['text'])
                _left = float(_f0['xmin']) * 1000 / sum[0]
                _pre = (float(_f0['middle']) - float(_f0['xmin'])) * 1000 / sum[3]
                if INITIAL_OFFSET_PREFETCH > 0:
                    _pp = min(INITIAL_OFFSET_PREFETCH, _left)
                    _left -= _pp
                    _pre += _pp
                _right = (float(_f0['xmax']) - float(_f0['middle'])) * 1000 / sum[2] + _pre
                _fixed = _pre if sum[1] == 0 else _pre + _right / sum[1]
                oto.append(f"{autio_name}.wav={_pname},{_left},{_fixed},-{_cut(_right, float(_f0['xmax']) * 1000, total_ms)},{_pre},{float(_pre) / sum[4]}\n")
        i = 0

        while i < len(sorted_phones)-1:
            key, cont = sorted_phones[i]
            key1, cont2 = sorted_phones[i + 1]
            # -CV规则。边界音素即使没写进 ignore 也按边界处理，
            # 否则首音素是 SP、而 ignore 只填了 AP,R 时，整句就生成不出「- 音素」。
            if (str(cont['text']) in _BND or cont['text'] in ignore) and (str(cont2['text']) not in _BND and cont2['text'] not in ignore):
                phone_name = '- ' + cont2['text']
                # autio_name=phone_name,left,fixed,right（负值）,Prevoice,cross
                left = float(cont2['xmin'])*1000/sum[0]

                Prevoice = (float(cont2['middle']) - float(cont2['xmin'])) * 1000 / sum[3]
                #右线占比
                right = (float(cont2['xmax'])-float(cont2['middle']))*1000/sum[2] + Prevoice
                # 固定的占比
                if sum[1] ==0:
                    fixed = Prevoice
                else:
                    fixed = Prevoice+(right-Prevoice)/sum[1]
                cross = float(Prevoice)/sum[4]
                i+=2
                # print(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(cont2["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")
                oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(cont2["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")
                continue
            # #CV规则
            # phone_name =cont['text']
            # # autio_name=phone_name,left,fixed,right（负值）,Prevoice,cross
            # left = float(cont['xmin']) * 1000 / sum[0]
            #
            # Prevoice = (float(cont['middle'])- float(cont['xmin'])) * 1000 / sum[3]
            # # 右线占比
            # right = (float(cont['xmax']) - float(cont['middle'])) * 1000 / sum[2] + Prevoice
            # # 固定的占比
            # if sum[1] == 0:
            #     fixed = Prevoice
            # else:
            #     fixed = Prevoice+(right-Prevoice)/sum[1]
            # cross = float(Prevoice) / sum[4]
            i += 1
            #
            # oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(cont["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")

    return oto

def json2VCVoto(cv_data,CV_V,sum,ignore):
    oto = []
    for audio_file, data in cv_data.items():
        autio_name = audio_file[:-4] if str(audio_file).lower().endswith(".wav") else audio_file
        phones = data.get('phones', {})
        long = data.get('long') or data.get('wav_long') or []
        total_ms = float(long[1]) * 1000 if len(long) >= 2 else 0.0
        if not phones:
            continue
        sorted_phones = sorted(phones.items(), key=lambda x: int(x[0]))
        # 剔除夹在音节之间的边界音素（R/SP/AP）：对齐偶尔会把音节之间的静音识别成 R，
        # 这道 R 会把「前音节→后音节」的相邻对切断，使本该是「i て」的条目退化成「- て」。
        # 这里按音节序列重新连接，首尾的边界仍保留（供句首/末尾分支判断）。
        _BND = ("R", "SP", "AP", "-", "sil")
        _n = len(sorted_phones)
        sorted_phones = [
            (k, v) for i, (k, v) in enumerate(sorted_phones)
            if not (1 <= i <= _n - 2 and str(v.get("text")) in _BND)
        ]
        # 句首兜底：若 json 第一条直接就是音节（没有 R 占位），也补一条「- 音素」，
        # 否则整句第一个音节只能匹配到「前一母音+本体」的 VCV 条目，
        # 在 OpenUtau 里会把前一个母音带出来（听起来像 a te）。
        if sorted_phones:
            first = sorted_phones[0][1]
            if first.get('text') not in ignore and first.get('text') in CV_V:
                phone_name = '- ' + first['text']
                left = float(first['xmin']) * 1000 / sum[0]
                Prevoice = (float(first['middle']) - float(first['xmin'])) * 1000 / sum[3]
                if INITIAL_OFFSET_PREFETCH > 0:
                    _p = min(INITIAL_OFFSET_PREFETCH, left)
                    left -= _p
                    Prevoice += _p
                right = (float(first['xmax']) - float(first['middle'])) * 1000 / sum[2] + Prevoice
                # 句首条目的播放区间必须接到下一条（第一个 VCV 条目）的起点，
                # 否则两条之间会留空档，合成时听感就是音被「切开」。
                if EXTEND_INITIAL_CUTOFF:
                    _next_off = (float(first['middle']) * 1000
                                 + (float(first['xmax']) - float(first['middle'])) * 1000 / sum[0])
                    right = max(right, _next_off - left + 20.0)
                if sum[1] == 0:
                    fixed = Prevoice
                else:
                    fixed = Prevoice + (float(first['xmax']) - float(first['middle'])) * 1000 / sum[1]
                cross = float(Prevoice) / sum[4]
                oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(first["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")
        i = 0
        while i < len(sorted_phones)-1:
            key, cont = sorted_phones[i]
            key1, cont1 = sorted_phones[i + 1]
            if str(cont['text']) in _BND or cont['text'] in ignore:
                # 句首（前面是 SP/AP）的音节额外补一条「- 音素」的前接标注，
                # 否则整句话第一个音节只有 VCV 条目、没有 -CV 条目。
                if str(cont1['text']) not in _BND and cont1['text'] not in ignore:
                    phone_name = '- ' + cont1['text']
                    # autio_name=phone_name,left,fixed,right（负值）,Prevoice,cross
                    left = float(cont1['xmin']) * 1000 / sum[0]
                    Prevoice = (float(cont1['middle']) - float(cont1['xmin'])) * 1000 / sum[3]
                    right = (float(cont1['xmax']) - float(cont1['middle'])) * 1000 / sum[2] + Prevoice
                    if EXTEND_INITIAL_CUTOFF:
                        _next_off = (float(cont1['middle']) * 1000
                                     + (float(cont1['xmax']) - float(cont1['middle'])) * 1000 / sum[0])
                        right = max(right, _next_off - left + 20.0)
                    if sum[1] == 0:
                        fixed = Prevoice
                    else:
                        fixed = Prevoice + (right - Prevoice) / sum[1]
                    cross = float(Prevoice) / sum[4]
                    oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(first["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")
                i += 1
                continue
            elif (str(cont1['text']) in _BND or cont1['text'] in ignore) and cont['text'] in CV_V:
                # 末尾/边界：别名统一写 R，不能把 SP/AP 原样拼进去
                phone_name = CV_V[cont['text']] + ' R'
                # autio_name=phone_name,left,fixed,right（负值）,Prevoice,cross
                left = float(cont["middle"]) * 1000 + ((float(cont['xmax']) - float(cont['middle'])) * 1000 / sum[0])
                # 右线占比
                Prevoice = float(cont1['middle']) * 1000 - left / sum[3]
                right = (float(cont1['xmax'])-float(cont1['middle']))*1000/sum[2] + Prevoice
                # 固定的占比
                if sum[1] ==0:
                    fixed = Prevoice
                else:
                    fixed = Prevoice + (float(cont1['xmax']) - float(cont1['middle'])) * 1000 / sum[1]
                cross = (float(cont['xmax']) - float(cont['middle'])) * 1000 / sum[4]
                i += 1
                oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(cont["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")
            elif  cont['text'] in CV_V and cont1['text'] in CV_V:
                phone_name = CV_V[cont['text']] + ' ' + cont1['text']
                # autio_name=phone_name,left,fixed,right（负值）,Prevoice,cross
                left = float(cont["middle"]) * 1000 + ((float(cont['xmax']) - float(cont['middle'])) * 1000 / sum[0])
                # 右线占比
                Prevoice = float(cont1['middle']) * 1000 - left / sum[3]
                right = (float(cont1['xmax'])-float(cont1['middle']))*1000/sum[2] + Prevoice
                # 固定的占比
                if sum[1] ==0:
                    fixed = Prevoice
                else:
                    fixed = Prevoice + (float(cont1['xmax']) - float(cont1['middle'])) * 1000 / sum[1]
                cross =(float(cont['xmax']) - float(cont['middle'])) *1000 / sum[4]
                i += 1
                oto.append(f"{autio_name}.wav={phone_name},{left},{fixed},-{_cut(right, float(cont1["xmax"]) * 1000, total_ms)},{Prevoice},{cross}\n")

            else:
                i+=1
                continue

        # 末尾兜底：json 末尾没有 R/SP 占位时，用末音节自己补一条 "母音 R"
        if sorted_phones:
            _lt = str(sorted_phones[-1][1].get('text'))
            if _lt in CV_V and _lt not in ('R', 'SP', 'AP', '-'):
                _last = sorted_phones[-1][1]
                _name = CV_V[_lt] + ' R'
                if not any(o.startswith(f"{autio_name}.wav={_name},") for o in oto):
                    left = float(_last['middle']) * 1000
                    Prevoice = float(_last['xmax']) * 1000 - left / sum[3]
                    right = Prevoice + 50
                    if sum[1] == 0:
                        fixed = Prevoice
                    else:
                        fixed = Prevoice + (float(_last['xmax']) - float(_last['middle'])) * 1000 / sum[1]
                    cross = (float(_last['xmax']) - float(_last['middle'])) * 1000 / sum[4]
                    oto.append(f"{autio_name}.wav={_name},{left},{fixed},-{_cut(right, float(_last['xmax']) * 1000, total_ms)},{Prevoice},{cross}\n")
    return oto

def run(presamp_path,word_phone_json,wav_path,cv_sum,vc_sum,vv_sum,ignore):
    ignore = ignore.split(',')
    CV_V = presamp_read(presamp_path)
    # 句首/句尾的 R 是 filter 把 SP 改名来的静音占位。UTAU 里「前接」
    # 一律用 -（- あ），而不是 R；R 只在后接位置（i R，长音）才有意义，
    # 所以这里让 R 作为前接前缀时输出 '-'。
    CV_V['R']='-'
    print(CV_V)
    # with open(utau_phone_json, 'r', encoding='utf-8') as f:
    #     vc_data = json.load(f)
    with open(word_phone_json, 'r', encoding='utf-8') as f:
        cv_data = json.load(f)
    oto = dedup_oto(json2cvoto(cv_data,cv_sum,ignore))
    # print(oto)
    with open(wav_path+'/cv_oto.ini', 'w', encoding='utf-8') as f:
        for i in oto:
            f.write(convert_line(i))
        print('cv_oto.ini生成成功')
    oto = dedup_oto(json2VCVoto(cv_data,CV_V, vc_sum,ignore))
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

    # presamp_path = 'presamp/樗儿式中文VCV-presamp.ini'
    # utau_phone = 'E:\\OpenUtau\\Singers\空气音中文VCV_自动oto测试/VCV/TextGrid/json/utau_phone.json'
    # word_phone_json = 'E:\\OpenUtau\\Singers\空气音中文VCV_自动oto测试/VCV/TextGrid/json/word_phone.json'
    # wav_path = 'E:\\OpenUtau\\Singers\空气音中文VCV_自动oto测试\VCV'

    presamp_path = r'F:\Textgrid2oto4\presamp\jp-hira-presamp.ini'
    word_phone_json = r'E:\OpenUtau\Singers\BaiNi_JP_VCV_0.1\1.C3/TextGrid/json/word_phone.json'
    wav_path = r'E:\OpenUtau\Singers\BaiNi_JP_VCV_0.1\1.C3'

    #-CV和CV规则：左线占比,固定的占比,右线占比,预发声不变,交叉占比
    cv_sum = [1,3,1.5,1,2]
    #VC和VV规则：左线占比,固定的占比,右线占比,预发声不变,交叉占比
    vc_sum=[1,3,1.5,1,2]
    vv_sum=[0,0,0,0,0]
    run(presamp_path,word_phone_json,wav_path,cv_sum,vc_sum,vv_sum)
