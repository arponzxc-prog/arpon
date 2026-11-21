package com.example.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.filesystem.HtmlBuildConfig
import com.example.data.filesystem.HtmlIconShape
import com.example.data.filesystem.HtmlMixedContentPolicy
import com.example.data.filesystem.HtmlOrientation
import com.example.data.filesystem.HtmlPermission
import com.example.data.filesystem.ProjectFileManager
import com.example.data.local.entity.ProjectEntity
import com.example.ui.theme.CoralRed
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val COLOR_PRESETS = listOf(
    "#0F172A",
    "#0284C7",
    "#7C3AED",
    "#059669",
    "#DC2626",
    "#D97706",
    "#111827",
    "#F8FAFC"
)

@Composable
fun HtmlBuildCustomizeDialog(
    project: ProjectEntity,
    fileManager: ProjectFileManager,
    suggestedVersionCode: Int,
    onDismiss: () -> Unit,
    onBuildWithConfig: (
        config: HtmlBuildConfig,
        logoUri: Uri?,
        splashUri: Uri?,
        startBuildImmediately: Boolean
    ) -> Unit
) {
    val initialConfig = remember(project.id) {
        val loaded = fileManager.loadHtmlBuildConfig(project)
        if (loaded.autoIncrementVersionCode) {
            loaded.copy(versionCode = maxOf(loaded.versionCode, suggestedVersionCode))
        } else {
            loaded
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Identity, 1: Icon & Splash, 2: WebView, 3: Permissions
    var appName by remember(project.id) { mutableStateOf(initialConfig.appName) }
    var packageName by remember(project.id) { mutableStateOf(initialConfig.packageName) }
    var versionName by remember(project.id) { mutableStateOf(initialConfig.versionName) }
    var versionCodeText by remember(project.id) { mutableStateOf(initialConfig.versionCode.toString()) }
    var autoIncrementVersionCode by remember(project.id) { mutableStateOf(initialConfig.autoIncrementVersionCode) }
    var orientation by remember(project.id) { mutableStateOf(initialConfig.orientation) }

    var iconShape by remember(project.id) { mutableStateOf(initialConfig.iconShape) }
    var enableAdaptiveIcon by remember(project.id) { mutableStateOf(initialConfig.enableAdaptiveIcon) }
    var enableMonochromeIcon by remember(project.id) { mutableStateOf(initialConfig.enableMonochromeIcon) }
    var iconBgColorHex by remember(project.id) { mutableStateOf(initialConfig.iconBgColorHex) }
    var showSplash by remember(project.id) { mutableStateOf(initialConfig.showSplash) }
    var splashTitle by remember(project.id) { mutableStateOf(initialConfig.splashTitle) }
    var splashBgColorHex by remember(project.id) { mutableStateOf(initialConfig.splashBgColorHex) }

    var remoteUrlMode by remember(project.id) { mutableStateOf(initialConfig.remoteUrlMode) }
    var remoteUrl by remember(project.id) { mutableStateOf(initialConfig.remoteUrl) }
    var fullscreen by remember(project.id) { mutableStateOf(initialConfig.fullscreen) }
    var statusBarColorHex by remember(project.id) { mutableStateOf(initialConfig.statusBarColorHex) }
    var pullToRefresh by remember(project.id) { mutableStateOf(initialConfig.pullToRefresh) }
    var offlineFallbackEnabled by remember(project.id) { mutableStateOf(initialConfig.offlineFallbackEnabled) }
    var enableJs by remember(project.id) { mutableStateOf(initialConfig.enableJs) }
    var enableDom by remember(project.id) { mutableStateOf(initialConfig.enableDom) }
    var enableZoom by remember(project.id) { mutableStateOf(initialConfig.enableZoom) }
    var enableJsBridge by remember(project.id) { mutableStateOf(initialConfig.enableJsBridge) }
    var mixedContentPolicy by remember(project.id) { mutableStateOf(initialConfig.mixedContentPolicy) }
    var allowCleartextTraffic by remember(project.id) { mutableStateOf(initialConfig.allowCleartextTraffic) }

    var selectedPermissions by remember(project.id) { mutableStateOf(initialConfig.permissions) }

    // Custom Images (Zero-permission system document picker supporting PNG/JPG/WEBP/SVG)
    var logoUri by remember(project.id) { mutableStateOf<Uri?>(null) }
    var logoPreviewBitmap by remember(project.id) { mutableStateOf<Bitmap?>(null) }
    var logoDecodeError by remember(project.id) { mutableStateOf<String?>(null) }

    var splashUri by remember(project.id) { mutableStateOf<Uri?>(null) }
    var splashPreviewBitmap by remember(project.id) { mutableStateOf<Bitmap?>(null) }
    var splashDecodeError by remember(project.id) { mutableStateOf<String?>(null) }

    val logoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) logoUri = uri
    }

    val splashPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) splashUri = uri
    }

    LaunchedEffect(logoUri) {
        val uri = logoUri
        if (uri == null) {
            logoPreviewBitmap = null
            logoDecodeError = null
        } else {
            val res = withContext(Dispatchers.IO) { fileManager.decodeBitmapWithExifAndSvg(uri) }
            if (res.isSuccess) {
                logoPreviewBitmap = res.getOrNull()
                logoDecodeError = null
            } else {
                logoPreviewBitmap = null
                logoDecodeError = res.exceptionOrNull()?.message ?: "Failed to decode logo image."
            }
        }
    }

    LaunchedEffect(splashUri) {
        val uri = splashUri
        if (uri == null) {
            splashPreviewBitmap = null
            splashDecodeError = null
        } else {
            val res = withContext(Dispatchers.IO) { fileManager.decodeBitmapWithExifAndSvg(uri) }
            if (res.isSuccess) {
                splashPreviewBitmap = res.getOrNull()
                splashDecodeError = null
            } else {
                splashPreviewBitmap = null
                splashDecodeError = res.exceptionOrNull()?.message ?: "Failed to decode splash image."
            }
        }
    }

    val isBangla = Locale.getDefault().language == "bn"
    val parsedVersionCode = versionCodeText.toIntOrNull() ?: 0
    val appNameError = HtmlBuildConfig.validateAppName(appName)
    val packageNameError = HtmlBuildConfig.validatePackageName(packageName)
    val versionNameError = HtmlBuildConfig.validateVersionName(versionName)
    val versionCodeError = HtmlBuildConfig.validateVersionCode(parsedVersionCode)
    val remoteUrlError = if (remoteUrlMode) HtmlBuildConfig.validateRemoteUrl(remoteUrl) else null

    val hasAnyError = appNameError != null ||
        packageNameError != null ||
        versionNameError != null ||
        versionCodeError != null ||
        remoteUrlError != null ||
        logoDecodeError != null ||
        splashDecodeError != null

    fun buildConfigSnapshot(): HtmlBuildConfig {
        val finalPerms = selectedPermissions.toMutableSet().apply {
            if (remoteUrlMode) add(HtmlPermission.INTERNET)
        }
        return HtmlBuildConfig(
            appName = appName.trim(),
            packageName = packageName.trim(),
            versionName = versionName.trim(),
            versionCode = parsedVersionCode.coerceAtLeast(1),
            autoIncrementVersionCode = autoIncrementVersionCode,
            orientation = orientation,
            fullscreen = fullscreen,
            statusBarColorHex = HtmlBuildConfig.normalizeHexColor(statusBarColorHex),
            remoteUrlMode = remoteUrlMode,
            remoteUrl = remoteUrl.trim(),
            pullToRefresh = pullToRefresh,
            offlineFallbackEnabled = offlineFallbackEnabled,
            enableJs = enableJs,
            enableDom = enableDom,
            enableZoom = enableZoom,
            showSplash = showSplash,
            splashTitle = splashTitle.trim().ifBlank { appName.trim() },
            splashBgColorHex = HtmlBuildConfig.normalizeHexColor(splashBgColorHex),
            iconBgColorHex = HtmlBuildConfig.normalizeHexColor(iconBgColorHex),
            iconShape = iconShape,
            enableAdaptiveIcon = enableAdaptiveIcon,
            enableMonochromeIcon = enableMonochromeIcon,
            mixedContentPolicy = mixedContentPolicy,
            allowCleartextTraffic = allowCleartextTraffic,
            enableJsBridge = enableJsBridge,
            permissions = finalPerms
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Slate900,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.webapp_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeonCyan
                )
                Text(
                    text = stringResource(R.string.webapp_dialog_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
            ) {
                // Horizontal Section Tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val tabs = listOf(
                        0 to stringResource(R.string.webapp_tab_identity),
                        1 to stringResource(R.string.webapp_tab_appearance),
                        2 to stringResource(R.string.webapp_tab_webview),
                        3 to stringResource(R.string.webapp_tab_permissions)
                    )
                    tabs.forEach { (idx, label) ->
                        FilterChip(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            label = { Text(label, fontSize = 12.sp) },
                            modifier = Modifier.testTag("webapp_tab_$idx")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (selectedTab) {
                        // TAB 0: IDENTITY & VERSION
                        0 -> {
                            OutlinedTextField(
                                value = appName,
                                onValueChange = { appName = it },
                                label = { Text(stringResource(R.string.webapp_app_name)) },
                                isError = appNameError != null,
                                supportingText = appNameError?.let { msg -> { Text(msg, color = CoralRed) } },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("webapp_app_name_input")
                            )

                            OutlinedTextField(
                                value = packageName,
                                onValueChange = { packageName = it.trim() },
                                label = { Text(stringResource(R.string.webapp_package_name)) },
                                isError = packageNameError != null,
                                supportingText = packageNameError?.let { msg -> { Text(msg, color = CoralRed) } },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("webapp_package_name_input")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = versionName,
                                    onValueChange = { versionName = it.trim() },
                                    label = { Text(stringResource(R.string.webapp_version_name)) },
                                    isError = versionNameError != null,
                                    supportingText = versionNameError?.let { msg -> { Text(msg, color = CoralRed) } },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = versionCodeText,
                                    onValueChange = { versionCodeText = it.filter { ch -> ch.isDigit() } },
                                    label = { Text(stringResource(R.string.webapp_version_code)) },
                                    isError = versionCodeError != null,
                                    supportingText = versionCodeError?.let { msg -> { Text(msg, color = CoralRed) } },
                                    singleLine = true,
                                    modifier = Modifier.weight(0.8f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${stringResource(R.string.webapp_auto_increment_version)} (#$suggestedVersionCode)",
                                    fontSize = 12.sp,
                                    color = Color.LightGray,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = autoIncrementVersionCode,
                                    onCheckedChange = { enabled ->
                                        autoIncrementVersionCode = enabled
                                        if (enabled) {
                                            val bumped = maxOf(parsedVersionCode, suggestedVersionCode)
                                            versionCodeText = bumped.toString()
                                        }
                                    }
                                )
                            }

                            Text(
                                text = stringResource(R.string.webapp_orientation_label),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                HtmlOrientation.entries.forEach { option ->
                                    FilterChip(
                                        selected = orientation == option,
                                        onClick = { orientation = option },
                                        label = {
                                            Text(
                                                text = if (isBangla) option.labelBn else option.labelEn,
                                                fontSize = 12.sp
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        // TAB 1: ICON & SPLASH + LIVE PREVIEW
                        1 -> {
                            LiveIconAndSplashPreviewCard(
                                appName = appName.ifBlank { project.name },
                                splashTitle = splashTitle.ifBlank { appName.ifBlank { project.name } },
                                showSplash = showSplash,
                                iconShape = iconShape,
                                iconBgColorHex = iconBgColorHex,
                                splashBgColorHex = splashBgColorHex,
                                logoBitmap = logoPreviewBitmap,
                                splashBitmap = splashPreviewBitmap ?: logoPreviewBitmap,
                                monochromeEnabled = enableMonochromeIcon
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        logoPickerLauncher.launch(arrayOf("image/*", "image/svg+xml"))
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonCyan)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.webapp_pick_logo), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (logoUri != null) {
                                    TextButton(onClick = { logoUri = null }) {
                                        Text(stringResource(R.string.webapp_clear_image), color = CoralRed, fontSize = 12.sp)
                                    }
                                }
                            }

                            logoDecodeError?.let { err ->
                                ErrorBanner(err)
                            }

                            Text(
                                text = stringResource(R.string.webapp_icon_shape),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HtmlIconShape.entries.forEach { shape ->
                                    FilterChip(
                                        selected = iconShape == shape,
                                        onClick = { iconShape = shape },
                                        label = { Text(shape.labelEn, fontSize = 12.sp) }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.webapp_adaptive_icon), fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f))
                                Switch(checked = enableAdaptiveIcon, onCheckedChange = { enableAdaptiveIcon = it })
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.webapp_monochrome_icon), fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = enableMonochromeIcon,
                                    enabled = enableAdaptiveIcon,
                                    onCheckedChange = { enableMonochromeIcon = it }
                                )
                            }

                            ColorPresetPicker(
                                label = stringResource(R.string.webapp_icon_bg_color),
                                selectedHex = iconBgColorHex,
                                onSelectHex = { iconBgColorHex = it }
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.webapp_show_splash), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
                                Switch(checked = showSplash, onCheckedChange = { showSplash = it })
                            }

                            if (showSplash) {
                                OutlinedTextField(
                                    value = splashTitle,
                                    onValueChange = { splashTitle = it },
                                    label = { Text(stringResource(R.string.webapp_splash_title)) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            splashPickerLauncher.launch(arrayOf("image/*", "image/svg+xml"))
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonPurple)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.webapp_pick_splash), fontSize = 12.sp)
                                    }
                                    if (splashUri != null) {
                                        TextButton(onClick = { splashUri = null }) {
                                            Text(stringResource(R.string.webapp_clear_image), color = CoralRed, fontSize = 12.sp)
                                        }
                                    }
                                }

                                splashDecodeError?.let { err ->
                                    ErrorBanner(err)
                                }

                                ColorPresetPicker(
                                    label = stringResource(R.string.webapp_splash_bg_color),
                                    selectedHex = splashBgColorHex,
                                    onSelectHex = { splashBgColorHex = it }
                                )
                            }
                        }

                        // TAB 2: WEBVIEW, NETWORK & SECURITY
                        2 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.webapp_remote_url_mode), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = NeonCyan, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = remoteUrlMode,
                                    onCheckedChange = { enabled ->
                                        remoteUrlMode = enabled
                                        if (enabled) {
                                            selectedPermissions = selectedPermissions + HtmlPermission.INTERNET
                                        }
                                    }
                                )
                            }

                            if (remoteUrlMode) {
                                OutlinedTextField(
                                    value = remoteUrl,
                                    onValueChange = { remoteUrl = it.trim() },
                                    label = { Text(stringResource(R.string.webapp_remote_url_label)) },
                                    isError = remoteUrlError != null,
                                    supportingText = remoteUrlError?.let { msg -> { Text(msg, color = CoralRed) } },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(stringResource(R.string.webapp_fullscreen_mode), fontSize = 12.sp, color = Color.White, modifier = Modifier.weight(1f))
                                Switch(checked = fullscreen, onCheckedChange = { fullscreen = it })
                            }

                            if (!fullscreen) {
                                ColorPresetPicker(
                                    label = stringResource(R.string.webapp_status_bar_color),
                                    selectedHex = statusBarColorHex,
                                    onSelectHex = { statusBarColorHex = it }
                                )
                            }

                            ToggleRow(stringResource(R.string.webapp_pull_to_refresh), pullToRefresh) { pullToRefresh = it }
                            ToggleRow(stringResource(R.string.webapp_offline_fallback), offlineFallbackEnabled) { offlineFallbackEnabled = it }
                            ToggleRow(stringResource(R.string.webapp_enable_js), enableJs) { enableJs = it }
                            ToggleRow(stringResource(R.string.webapp_enable_dom), enableDom) { enableDom = it }
                            ToggleRow(stringResource(R.string.webapp_enable_zoom), enableZoom) { enableZoom = it }
                            ToggleRow(stringResource(R.string.webapp_enable_js_bridge), enableJsBridge) { enableJsBridge = it }

                            Text(
                                text = stringResource(R.string.webapp_mixed_content_label),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                HtmlMixedContentPolicy.entries.forEach { policy ->
                                    FilterChip(
                                        selected = mixedContentPolicy == policy,
                                        onClick = { mixedContentPolicy = policy },
                                        label = { Text(policy.labelEn, fontSize = 11.sp) }
                                    )
                                }
                            }

                            ToggleRow(stringResource(R.string.webapp_cleartext_traffic), allowCleartextTraffic) {
                                allowCleartextTraffic = it
                            }
                        }

                        // TAB 3: PERMISSIONS CHECKLIST
                        3 -> {
                            Surface(
                                color = NeonCyan.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.35f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Security, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = stringResource(R.string.webapp_permissions_hint),
                                        fontSize = 11.sp,
                                        color = Color.LightGray,
                                        lineHeight = 15.sp
                                    )
                                }
                            }

                            HtmlPermission.entries.forEach { perm ->
                                val isForcedInternet = perm == HtmlPermission.INTERNET && remoteUrlMode
                                val checked = selectedPermissions.contains(perm) || isForcedInternet
                                Surface(
                                    color = Slate950,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, if (checked) NeonCyan.copy(alpha = 0.5f) else Slate800),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !isForcedInternet) {
                                            selectedPermissions = if (checked) {
                                                selectedPermissions - perm
                                            } else {
                                                selectedPermissions + perm
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            enabled = !isForcedInternet,
                                            onCheckedChange = { nowChecked ->
                                                selectedPermissions = if (nowChecked) {
                                                    selectedPermissions + perm
                                                } else {
                                                    selectedPermissions - perm
                                                }
                                            }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = if (isBangla) perm.labelBn else perm.labelEn,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White
                                                )
                                                if (perm.isDangerous) {
                                                    Surface(
                                                        color = NeonPurple.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(4.dp)
                                                    ) {
                                                        Text(
                                                            text = "LAZY PROMPT",
                                                            fontSize = 9.sp,
                                                            color = NeonPurple,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = perm.descriptionEn,
                                                fontSize = 11.sp,
                                                color = Color.Gray,
                                                lineHeight = 14.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        onBuildWithConfig(buildConfigSnapshot(), logoUri, splashUri, false)
                    },
                    enabled = !hasAnyError
                ) {
                    Text(stringResource(R.string.webapp_save_only), fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        onBuildWithConfig(buildConfigSnapshot(), logoUri, splashUri, true)
                    },
                    enabled = !hasAnyError,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                    modifier = Modifier.testTag("webapp_confirm_build_button")
                ) {
                    Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.webapp_build_now), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.webapp_cancel))
            }
        }
    )
}

@Composable
private fun LiveIconAndSplashPreviewCard(
    appName: String,
    splashTitle: String,
    showSplash: Boolean,
    iconShape: HtmlIconShape,
    iconBgColorHex: String,
    splashBgColorHex: String,
    logoBitmap: Bitmap?,
    splashBitmap: Bitmap?,
    monochromeEnabled: Boolean
) {
    val iconBgColor = remember(iconBgColorHex) { parseSafeColor(iconBgColorHex, Slate950) }
    val splashBgColor = remember(splashBgColorHex) { parseSafeColor(splashBgColorHex, Slate950) }
    val monogram = appName.trim().take(2).uppercase().ifBlank { "NX" }
    val iconShapeClip = if (iconShape == HtmlIconShape.CIRCLE) CircleShape else RoundedCornerShape(16.dp)

    Surface(
        color = Slate950,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Slate800),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.webapp_preview_heading),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = NeonCyan
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // App Icon Preview
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(iconShapeClip)
                            .background(iconBgColor)
                            .border(1.dp, Slate700, iconShapeClip),
                        contentAlignment = Alignment.Center
                    ) {
                        if (logoBitmap != null) {
                            Image(
                                bitmap = logoBitmap.asImageBitmap(),
                                contentDescription = "App icon preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp)
                                    .clip(iconShapeClip)
                            )
                        } else {
                            Text(
                                text = monogram,
                                color = if (isLightColor(iconBgColor)) Color.Black else Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 20.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (monochromeEnabled) "${iconShape.labelEn} • Mono" else iconShape.labelEn,
                        fontSize = 10.sp,
                        color = Color.LightGray,
                        maxLines = 1
                    )
                }

                // Splash Preview Card
                Surface(
                    color = splashBgColor,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Slate700),
                    modifier = Modifier
                        .weight(1f)
                        .height(86.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!showSplash) {
                            Text(
                                text = "Splash Disabled (Direct WebView)",
                                fontSize = 11.sp,
                                color = if (isLightColor(splashBgColor)) Color.DarkGray else Color.Gray
                            )
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.12f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (splashBitmap != null) {
                                        Image(
                                            bitmap = splashBitmap.asImageBitmap(),
                                            contentDescription = "Splash logo preview",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = monogram,
                                            color = if (isLightColor(splashBgColor)) Color.Black else Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                                Column {
                                    Text(
                                        text = splashTitle,
                                        color = if (isLightColor(splashBgColor)) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "Splash Screen Preview",
                                        color = if (isLightColor(splashBgColor)) Color.DarkGray else Color.LightGray,
                                        fontSize = 10.sp
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

@Composable
private fun ColorPresetPicker(
    label: String,
    selectedHex: String,
    onSelectHex: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Text(selectedHex, fontSize = 11.sp, color = NeonCyan)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            COLOR_PRESETS.forEach { hex ->
                val color = parseSafeColor(hex, Slate900)
                val isSelected = selectedHex.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 2.5.dp else 1.dp,
                            color = if (isSelected) NeonCyan else Slate700,
                            shape = CircleShape
                        )
                        .clickable { onSelectHex(hex) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected color $hex",
                            tint = if (isLightColor(color)) Color.Black else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = Color.LightGray, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = CoralRed.copy(alpha = 0.14f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, CoralRed.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = CoralRed, modifier = Modifier.size(16.dp))
            Text(message, color = CoralRed, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

private fun parseSafeColor(hex: String, fallback: Color): Color {
    return try {
        Color(android.graphics.Color.parseColor(HtmlBuildConfig.normalizeHexColor(hex)))
    } catch (_: Exception) {
        fallback
    }
}

private fun isLightColor(color: Color): Boolean {
    val luminance = (0.299f * color.red + 0.587f * color.green + 0.114f * color.blue)
    return luminance > 0.65f
}
