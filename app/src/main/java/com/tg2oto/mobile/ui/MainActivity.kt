package com.tg2oto.mobile.ui

import android.os.Bundle
import android.os.LocaleList
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.rotate
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.ripple
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tg2oto.mobile.R
import com.tg2oto.mobile.data.AppSettings
import com.tg2oto.mobile.data.DarkMode
import com.tg2oto.mobile.ui.components.LogPanel
import com.tg2oto.mobile.ui.components.ProgressStrip
import com.tg2oto.mobile.ui.screens.AlignScreen
import com.tg2oto.mobile.ui.screens.JsonScreen
import com.tg2oto.mobile.ui.screens.LabScreen
import com.tg2oto.mobile.ui.screens.OtoScreen
import com.tg2oto.mobile.ui.screens.LicensesScreen
import com.tg2oto.mobile.ui.screens.ToolsScreen
import com.tg2oto.mobile.ui.screens.ModelScreen
import com.tg2oto.mobile.ui.screens.DictScreen
import com.tg2oto.mobile.core.Backend
import com.tg2oto.mobile.core.SocInfo
import com.tg2oto.mobile.ui.theme.AppTheme
import com.tg2oto.mobile.ui.theme.RingColorPicker
import com.tg2oto.mobile.util.StorageAccess
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.tg2oto.mobile.ui.i18n.L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: AppViewModel = viewModel()
            val settings by vm.settings.collectAsState()
            // 系统语言只在启动时取一次：应用内切换语言不会改系统 Locale。
            val systemEnglish = remember {
                val tag = LocaleList.getDefault()[0] ?: Locale.getDefault()
                tag.language != "zh"
            }
            LaunchedEffect(settings.language, systemEnglish) {
                L.configure(settings.language.ifBlank { null }, systemEnglish)
            }
            // 拖动取色时只更新内存里的预览色，松手才写 DataStore。
            // 之前每个 move 节流 40ms 就写一次盘，写盘 + 主题重组叠在一起就是卡顿来源。
            var seedPreview by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(settings.seedColor) {
                if (settings.seedColor == seedPreview) seedPreview = null
            }
            AppTheme(
                seed = Color(seedPreview ?: settings.seedColor),
                darkMode = settings.darkMode,
                dynamicColor = settings.dynamicColor
            ) {
                AppScaffold(vm, settings, seedPreview) { seedPreview = it }
            }
        }
    }
}

private data class TabSpec(
    val label: String,
    val icon: Int
)

private data class PageKey(val tab: Int, val overlay: String?)

private val TABS = listOf(
    TabSpec("标注", R.drawable.ic_ms_tag),
    TabSpec("对齐", R.drawable.ic_ms_graphic_eq),
    TabSpec("JSON", R.drawable.ic_ms_data_object),
    TabSpec("oto", R.drawable.ic_ms_library_music)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    vm: AppViewModel,
    settings: AppSettings,
    seedPreview: Int?,
    onSeedPreview: (Int?) -> Unit
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var logExpanded by remember { mutableStateOf(true) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var overlay by remember { mutableStateOf<String?>(null) }
    var drawerPage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed) drawerPage = null
    }

    val models by vm.models.collectAsState()
    val install by vm.installProgress.collectAsState()
    var needsPermission by remember { mutableStateOf(!StorageAccess.hasAllFiles(context)) }

    // 从「所有文件访问权限」设置页返回时必须重新判定，否则授权后横幅不会消失
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                needsPermission = !StorageAccess.hasAllFiles(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        vm.refreshModels()
        vm.refreshDicts()
        vm.loadResources()
    }

    val crash by vm.crash.collectAsState()
    val backendReport by vm.backendReport.collectAsState()
    val clipboard = LocalClipboardManager.current
    crash?.let { info ->
        val fullText = buildString {
            append(L.s("TextGrid2oto Mobile 推理进程日志\n"))
            append(L.s("—— 错误 ——\n"))
            append(info.detail).append("\n\n")
            append(L.s("—— 最近日志 ——\n"))
            append(info.logs.joinToString("\n"))
        }
        AlertDialog(
            onDismissRequest = { vm.dismissCrash() },
            title = { Text(L.s("推理进程崩溃")) },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        info.detail.ifBlank { L.s("推理进程意外退出") },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (info.logs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Text(L.s("最近日志"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            info.logs.joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(fullText))
                    vm.dismissCrash()
                }) { Text(L.s("复制日志")) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissCrash() }) { Text(L.s("关闭")) }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                vm = vm,
                settings = settings,
                page = drawerPage,
                onPage = { drawerPage = it },
                onTab = { tab = it },
                onTheme = { showThemeSheet = true },
                onModels = { overlay = "models" },
                onDicts = { overlay = "dicts" },
                onTools = { overlay = "tools" },
                onAbout = { overlay = "licenses" },
                onClose = { scope.launch { drawerState.close() } }
            )
        }
    ) {
    // 小工具抽屉与主界面同层：抽屉作为 Box 的最后一个子项，
    // 才能盖住 Scaffold 的 bottomBar（底部导航栏）。
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TextGrid2oto Mobile", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            L.s("TextGrid → oto.ini  ·  移动版"),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(painterResource(R.drawable.ic_ms_menu), L.s("菜单"))
                    }
                },
                actions = {
                    IconButton(onClick = { logExpanded = !logExpanded }) {
                        Icon(painterResource(R.drawable.ic_ms_terminal), L.s("日志"))
                    }
                    IconButton(onClick = { showThemeSheet = true }) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_ms_palette), L.s("主题色"))
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .background(Color(settings.seedColor), CircleShape)
                                    .align(Alignment.BottomEnd)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Column {
                if (needsPermission) {
                    PermissionBanner { StorageAccess.requestAllFiles(context) }
                }
                // 自定义底部导航：固定高度 + 一个随选中项滑动的「水滴」指示器。
                // 注意这里不能用 fillMaxHeight()，bottomBar 测量时高度无上限，
                // 会把整个底部栏撑成满屏、并把顶栏和内容挤出屏幕。
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                        // 70dp 的点击区，手势条 inset 单独让出来，整格都能点
                        BoxWithConstraints(Modifier.fillMaxWidth().height(70.dp)) {
                            val itemWidth = maxWidth / TABS.size
                            val indicatorX by animateDpAsState(
                                targetValue = itemWidth * tab,
                                // 原来用低刚度 spring，起步几十毫秒几乎不动，看着就是"点了没反应"
                                animationSpec = tween(160, easing = LinearOutSlowInEasing),
                                label = "indicator"
                            )
                            Box(
                                Modifier
                                    .offset(x = indicatorX)
                                    .width(itemWidth)
                                    .fillMaxHeight()
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                            )
                            Row(Modifier.fillMaxSize()) {
                                TABS.forEachIndexed { index, spec ->
                                    val selected = tab == index
                                    val interaction = remember { MutableInteractionSource() }
                                    val pressed by interaction.collectIsPressedAsState()
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            // 按下立刻出一块淡色，松手马上消失；不是水波扩散
                                            .background(
                                                if (pressed && !selected) {
                                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
                                                } else {
                                                    Color.Transparent
                                                }
                                            )
                                            .clickable(
                                                interactionSource = interaction,
                                                indication = null,
                                                role = Role.Tab
                                            ) { tab = index },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                painterResource(spec.icon),
                                                L.s(spec.label),
                                                Modifier.size(21.dp),
                                                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                L.s(spec.label),
                                                fontSize = 11.sp,
                                                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // 所有页面切换走同一套过渡：同级切换轻微位移淡入，
            // 进/出二级页则是「新卡片从底部升起 + 旧卡片缩小后退」的堆叠效果。
            AnimatedContent(
                // 小工具改走独立抽屉（要盖住导航栏），不参与这里的页面切换动画：
                // 置成 null 让背景停在当前页，由抽屉自己滑入。
                targetState = PageKey(tab, if (overlay == "tools") null else overlay),
                transitionSpec = {
                    val overlayChanged = initialState.overlay != targetState.overlay
                    if (overlayChanged) {
                        (
                            slideInVertically(tween(300, easing = FastOutSlowInEasing)) { it } +
                                fadeIn(tween(220))
                            ).togetherWith(
                            scaleOut(tween(300), targetScale = 0.92f) + fadeOut(tween(300))
                        ).using(SizeTransform(clip = false))
                    } else {
                        // 页面跟随按钮的左右顺序：往右边的 Tab 切，新页从右边进来，
                        // 旧页朝左退出；反向同理。
                        val dir = if (targetState.tab >= initialState.tab) 1 else -1
                        (
                            slideInHorizontally(
                                tween(200, easing = FastOutSlowInEasing)
                            ) { it * dir } + fadeIn(tween(140))
                            ).togetherWith(
                            slideOutHorizontally(
                                tween(200, easing = FastOutSlowInEasing)
                            ) { -it * dir } + fadeOut(tween(140))
                        )
                    }
                },
                label = "page"
            ) { key ->
                // 全屏 Surface 不要加 shadowElevation 和圆角：两者都会让整块内容
                // 走离屏缓冲，滚动时每帧都要重新合成，是掉帧的主要来源。
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        key.overlay == "models" -> ModelScreen(vm) { overlay = null }
                        key.overlay == "dicts" -> DictScreen(vm) { overlay = null }
                        key.overlay == "licenses" -> LicensesScreen { overlay = null }
                        else -> when (key.tab) {
                            0 -> LabScreen(vm)
                            1 -> AlignScreen(
                                vm = vm,
                                models = models.filter { it.type == "hubertfa" || it.type == "tifa" },
                                selectedModelId = settings.selectedModelId.ifBlank {
                                    models.firstOrNull { it.type == "hubertfa" }?.id ?: ""
                                },
                                onSelectModel = { model ->
                                    scope.launch { vm.settingsStore().setModelId(model.id) }
                                }
                            )
                            2 -> JsonScreen(
                                vm = vm,
                                models = models.filter { it.type == "hubertfa" || it.type == "tifa" },
                                selectedModelId = settings.selectedModelId
                            )
                            else -> OtoScreen(vm)
                        }
                    }
                }
            }

            // 日志面板固定贴在内容区底部，拖动它的标题栏改变展开高度（抽屉式）
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                ProgressStrip(
                    runningFlow = vm.running,
                    progressFlow = vm.progress,
                    statusFlow = vm.status
                )
                LogPanel(
                    logsFlow = vm.logs,
                    expanded = logExpanded,
                    onToggle = { logExpanded = !logExpanded },
                    onClear = vm::clearLogs
                )
            }
        }
    }
    }

    // 小工具抽屉：从屏幕最下方升到整屏。它挂在 Scaffold 之外，
    // 因此会盖住 bottomBar（底部导航栏）——导航栏被压在抽屉页面下方。
    // FastOutSlowInEasing 是先快后慢的减速曲线，进、出都用 400ms。
    AnimatedVisibility(
        visible = overlay == "tools",
        enter = slideInVertically(tween(400, easing = FastOutSlowInEasing)) { it } +
            fadeIn(tween(150)),
        exit = slideOutVertically(tween(400, easing = FastOutSlowInEasing)) { it } +
            fadeOut(tween(200))
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                ToolsScreen(vm) { overlay = null }
            }
        }
    }
    }

    backendReport?.let { report ->
        AlertDialog(
            onDismissRequest = { vm.dismissBackendReport() },
            title = { Text(L.s("推理后端检测")) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(report, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(report))
                    vm.dismissBackendReport()
                }) { Text(L.s("复制报告")) }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissBackendReport() }) { Text(L.s("关闭")) }
            }
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(L.s("关于")) },
            text = {
                Column {
                    Text("TextGrid2oto Mobile", fontWeight = FontWeight.SemiBold)
                    Text(L.s("基于 xiaobaijunya/TextGrid2oto（GPL-3.0）"), fontSize = 12.sp)
                    Text(L.s("本移植同样以 GPL-3.0 发布，完整源码随工程提供。"), fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text(L.s("好的")) } }
        )
    }

    if (showThemeSheet) {
        ThemeSheet(
            settings = settings,
            onDismiss = { showThemeSheet = false },
            onColorChange = { color -> onSeedPreview(color) },
            onColorCommit = { color ->
                onSeedPreview(color)
                scope.launch { vm.settingsStore().setSeed(color) }
            },
            onDarkModeChange = { mode -> scope.launch { vm.settingsStore().setDarkMode(mode) } },
            onDynamicChange = { enabled -> scope.launch { vm.settingsStore().setDynamic(enabled) } }
        )
    }
}

@Composable
private fun PermissionBanner(onRequest: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                L.s("需要「所有文件访问权限」才能直接读写录音目录"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRequest) { Text(L.s("去授权")) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSheet(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onColorChange: (Int) -> Unit,
    onColorCommit: (Int) -> Unit,
    onDarkModeChange: (DarkMode) -> Unit,
    onDynamicChange: (Boolean) -> Unit
) {
    // 用贴底 Dialog 而不是 ModalBottomSheet：M3 的 sheet 拖拽手势会抢走
    // 取色器和亮度滑条的触摸，且下拉关闭的判定阈值偏低，容易误触。
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(L.s("外观与主题色"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            Text(L.s("显示模式"), style = MaterialTheme.typography.labelMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf(
                    DarkMode.SYSTEM to L.s("跟随系统"),
                    DarkMode.LIGHT to L.s("浅色"),
                    DarkMode.DARK to L.s("深色")
                )
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = settings.darkMode == mode,
                        onClick = { onDarkModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(L.s("动态取色"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        L.s("Android 12 及以上可从壁纸取色（会覆盖下面的手选颜色）"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = settings.dynamicColor, onCheckedChange = onDynamicChange)
            }

            RingColorPicker(
                color = Color(settings.seedColor),
                onColorChange = { onColorChange(it.toArgbInt()) },
                onColorCommit = { onColorCommit(it.toArgbInt()) }
            )

            Spacer(Modifier.height(16.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(L.s("完成")) }
            Spacer(Modifier.height(20.dp))
        }
        }
      }
    }
}

private fun Color.toArgbInt(): Int = this.toArgb()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppDrawer(
    vm: AppViewModel,
    settings: AppSettings,
    page: String?,
    onPage: (String?) -> Unit,
    onTab: (Int) -> Unit,
    onTheme: () -> Unit,
    onModels: () -> Unit,
    onDicts: () -> Unit,
    onTools: () -> Unit,
    onAbout: () -> Unit,
    onClose: () -> Unit
) {
    val soc = remember { SocInfo.detect() }
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
                Text("TextGrid2oto Mobile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (page == null) L.s("TextGrid → oto.ini · 移动版") else L.s("侧边栏 · 上一层可返回"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider()

            when (page) {
                null -> {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(L.s("工作流"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    TABS.forEachIndexed { index, spec ->
                        NavigationDrawerItem(
                            label = { Text(L.s(spec.label)) },
                            icon = { Icon(painterResource(spec.icon), null) },
                            selected = false,
                            onClick = { onClose(); onTab(index) }
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text(L.s("资源管理")) },
                        icon = { Icon(painterResource(R.drawable.ic_ms_model_training), null) },
                        selected = false,
                        onClick = { onPage("res") }
                    )
                    NavigationDrawerItem(
                        label = { Text(L.s("小工具")) },
                        icon = { Icon(painterResource(R.drawable.ic_ms_widgets), null) },
                        selected = false,
                        onClick = { onClose(); onTools() }
                    )
                    NavigationDrawerItem(
                        label = { Text(L.s("设置")) },
                        icon = { Icon(painterResource(R.drawable.ic_ms_settings), null) },
                        selected = false,
                        onClick = { onPage("set") }
                    )
                    NavigationDrawerItem(
                        label = { Text(L.s("关于与许可")) },
                        icon = { Icon(painterResource(R.drawable.ic_ms_info), null) },
                        selected = false,
                        onClick = { onClose(); onAbout() }
                    )
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                        Text(
                            L.s("GPL-3.0 · 基于 xiaobaijunya/TextGrid2oto"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                "res" -> {
                    DrawerBack(onPage)
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(L.s("资源管理"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    NavigationDrawerItem(
                        label = { Text(L.s("模型管理")) },
                        icon = { Icon(painterResource(R.drawable.ic_ms_model_training), null) },
                        selected = false,
                        onClick = { onClose(); onModels() }
                    )
                    NavigationDrawerItem(
                        label = { Text(L.s("字典库")) },
                        icon = { Icon(painterResource(R.drawable.ic_ms_translate), null) },
                        selected = false,
                        onClick = { onClose(); onDicts() }
                    )
                }

                else -> {
                    DrawerBack(onPage)
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(L.s("设置"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                    NavigationDrawerItem(
                        label = { Text(L.s("外观与主题")) },
                        icon = { Icon(painterResource(R.drawable.ic_ms_palette), null) },
                        selected = false,
                        onClick = { onClose(); onTheme() }
                    )
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(L.s("界面语言"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("" to "跟随系统", "zh" to "中文", "en" to "English").forEach { (value, label) ->
                                FilterChip(
                                    selected = settings.language == value,
                                    onClick = { vm.setLanguage(value) },
                                    label = { Text(L.s(label), fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                        Text(L.s("推理后端"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            (if (L.isEnglish) "Chip " else "芯片 ") + L.s(SocInfo.label(soc)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(Backend.AUTO, Backend.CPU, Backend.NNAPI_HW, Backend.NNAPI).forEach { backend ->
                                FilterChip(
                                    selected = settings.backend == backend.id,
                                    onClick = { vm.setBackend(backend.id) },
                                    label = { Text(L.s(backend.label), fontSize = 11.sp) }
                                )
                            }
                        }
                        Text(
                            L.s(Backend.of(settings.backend).desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { onClose(); vm.probeBackends() }) {
                            Icon(painterResource(R.drawable.ic_ms_tune), null, Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(L.s("检测设备能力"), fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { onClose(); vm.benchBackends() }) {
                            Icon(painterResource(R.drawable.ic_ms_compare_arrows), null, Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(L.s("真实模型基准测试"), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerBack(onPage: (String?) -> Unit) {
    NavigationDrawerItem(
        label = { Text(L.s("返回上一层")) },
        icon = {
            Icon(
                painterResource(R.drawable.ic_ms_chevron_right),
                null,
                Modifier.rotate(180f)
            )
        },
        selected = false,
        onClick = { onPage(null) }
    )
}
