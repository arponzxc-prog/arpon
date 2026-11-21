package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.assets.AssetStudioProcessor
import com.example.assets.IconShapeOption
import com.example.assets.ImageExportFormat
import com.example.assets.ImportedAssetInfo
import com.example.data.local.entity.ProjectEntity
import com.example.ui.theme.AmberYellow
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.NeonCyan
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AssetStudioScreen(
    project: ProjectEntity?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Image Tools", "Icon Studio", "Splash", "Design Tools", "Project Assets")

    // Imported Image State
    var importedAsset by remember { mutableStateOf<ImportedAssetInfo?>(null) }
    var editedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var targetResizeW by remember { mutableStateOf("512") }
    var targetResizeH by remember { mutableStateOf("512") }
    var selectedExportFormat by remember { mutableStateOf(ImageExportFormat.PNG) }
    var selectedTargetFolder by remember { mutableStateOf("app/src/main/res/drawable") }
    var exportFileName by remember { mutableStateOf("custom_asset") }

    // Icon Studio State
    var iconBgColorInt by remember { mutableIntStateOf(android.graphics.Color.parseColor("#0F172A")) }
    var iconFgColorInt by remember { mutableIntStateOf(android.graphics.Color.parseColor("#00D2FF")) }
    var iconShape by remember { mutableStateOf(IconShapeOption.SQUIRCLE) }
    var iconMonogram by remember { mutableStateOf(project?.name?.take(2)?.uppercase() ?: "NX") }
    var iconPadding by remember { mutableFloatStateOf(0.18f) }
    var useImportedAsIconFg by remember { mutableStateOf(false) }

    // Splash Designer State
    var splashBgHex by remember { mutableStateOf("#090D16") }
    var splashTitle by remember { mutableStateOf(project?.name ?: "Nexa App") }
    var splashTextColorHex by remember { mutableStateOf("#FFFFFF") }

    // Design Tools Color Picker & Palette State
    var redVal by remember { mutableFloatStateOf(0f) }
    var greenVal by remember { mutableFloatStateOf(210f) }
    var blueVal by remember { mutableFloatStateOf(255f) }
    val savedPalette = remember {
        mutableStateListOf("#00D2FF", "#A855F7", "#10B981", "#F59E0B", "#EF4444", "#090D16")
    }
    var fontPreviewText by remember { mutableStateOf("Nexa IDE Typography Preview 123") }
    var fontPreviewSize by remember { mutableFloatStateOf(16f) }

    // Project Assets refresh key
    var assetsRefreshTrigger by remember { mutableIntStateOf(0) }
    val projectAssets = remember(project?.rootPath, assetsRefreshTrigger) {
        project?.let { AssetStudioProcessor.listProjectVisualAssets(File(it.rootPath)) } ?: emptyList()
    }

    // Overwrite Confirmation Dialog State
    var pendingOverwriteAction by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    fun queryDisplayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return "asset_${System.currentTimeMillis()}.png"
    }

    fun handlePickedUri(uri: Uri) {
        val name = queryDisplayName(uri)
        val result = AssetStudioProcessor.decodeImageUri(context, uri, name)
        result.fold(
            onSuccess = { info ->
                importedAsset = info
                editedBitmap = info.bitmap
                targetResizeW = info.width.toString()
                targetResizeH = info.height.toString()
                exportFileName = name.substringBeforeLast(".").replace(Regex("[^a-zA-Z0-9_]"), "_").lowercase().ifBlank { "asset" }
                Toast.makeText(context, "Loaded ${info.width}x${info.height} (${info.byteSize / 1024} KB)", Toast.LENGTH_SHORT).show()
            },
            onFailure = { err ->
                Toast.makeText(context, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    // Zero-permission Photo Picker
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { handlePickedUri(it) } }

    // Document picker for SVG or specific files
    val svgOrFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { handlePickedUri(it) } }

    fun saveFileSafely(destFile: File, bytes: ByteArray) {
        val executeWrite = {
            destFile.parentFile?.mkdirs()
            FileOutputStream(destFile).use { it.write(bytes) }
            assetsRefreshTrigger++
            Toast.makeText(context, "Saved: ${destFile.name}", Toast.LENGTH_SHORT).show()
        }
        if (destFile.exists()) {
            pendingOverwriteAction = "Overwrite existing '${destFile.name}' at:\n${destFile.absolutePath}" to executeWrite
        } else {
            executeWrite()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate950)
            .navigationBarsPadding()
            .testTag("asset_studio_screen")
    ) {
        TopAppBar(
            title = {
                Column {
                    Text("Asset Studio", fontWeight = FontWeight.Bold)
                    Text(
                        text = project?.name ?: "Standalone Preview",
                        fontSize = 11.sp,
                        color = NeonCyan
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Slate900, titleContentColor = Color.White)
        )

        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            containerColor = Slate900,
            contentColor = NeonCyan,
            edgePadding = 12.dp
        ) {
            tabs.forEachIndexed { idx, label ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = {
                        Text(label, fontSize = 13.sp, fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Normal)
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> {
                // Tab 0: Image Tools (Import, Resize, Crop, Format Convert, Safe Save)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            border = BorderStroke(1.dp, Slate800)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Import Image or SVG", fontWeight = FontWeight.Bold, color = Color.White)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = {
                                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate950),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Photo Picker", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            svgOrFilePicker.launch(arrayOf("image/svg+xml", "image/*"))
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("SVG / File", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    editedBitmap?.let { bmp ->
                        item {
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = Slate900),
                                border = BorderStroke(1.dp, NeonPurple.copy(alpha = 0.4f))
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Preview & Dimensions", fontWeight = FontWeight.Bold, color = Color.White)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(180.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF1E293B)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = "Asset Preview",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize().padding(8.dp)
                                        )
                                    }
                                    val encodedSize = AssetStudioProcessor.encodeBitmap(bmp, selectedExportFormat).size
                                    Text(
                                        text = "Dimensions: ${bmp.width} x ${bmp.height} px • Est. Output: ${encodedSize / 1024} KB",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = NeonCyan
                                    )

                                    // Crop Presets
                                    Text("Crop Aspect", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("1:1" to (1 to 1), "4:3" to (4 to 3), "16:9" to (16 to 9)).forEach { (label, ratio) ->
                                            OutlinedButton(
                                                onClick = {
                                                    val cropped = AssetStudioProcessor.cropCenterAspect(bmp, ratio.first, ratio.second)
                                                    editedBitmap = cropped
                                                    targetResizeW = cropped.width.toString()
                                                    targetResizeH = cropped.height.toString()
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Icon(Icons.Default.Crop, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(label, fontSize = 11.sp)
                                            }
                                        }
                                    }

                                    // Resize Controls
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = targetResizeW,
                                            onValueChange = { targetResizeW = it.filter { ch -> ch.isDigit() } },
                                            label = { Text("Width px") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = targetResizeH,
                                            onValueChange = { targetResizeH = it.filter { ch -> ch.isDigit() } },
                                            label = { Text("Height px") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                val w = targetResizeW.toIntOrNull() ?: bmp.width
                                                val h = targetResizeH.toIntOrNull() ?: bmp.height
                                                editedBitmap = AssetStudioProcessor.resizeBitmap(bmp, w, h)
                                            }
                                        ) { Text("Resize") }
                                    }

                                    // Format Conversion
                                    Text("Export Format", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        ImageExportFormat.entries.forEach { fmt ->
                                            FilterChip(
                                                selected = selectedExportFormat == fmt,
                                                onClick = { selectedExportFormat = fmt },
                                                label = { Text(fmt.label, fontSize = 11.sp) }
                                            )
                                        }
                                    }

                                    // Target Android Resource Folder
                                    Text("Destination Directory", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(
                                            "app/src/main/res/drawable",
                                            "app/src/main/res/drawable-xhdpi",
                                            "app/src/main/res/drawable-xxhdpi",
                                            "app/src/main/assets"
                                        ).forEach { folder ->
                                            FilterChip(
                                                selected = selectedTargetFolder == folder,
                                                onClick = { selectedTargetFolder = folder },
                                                label = { Text(folder.substringAfterLast("/"), fontSize = 11.sp) }
                                            )
                                        }
                                    }

                                    OutlinedTextField(
                                        value = exportFileName,
                                        onValueChange = { exportFileName = it.filter { ch -> ch.isLetterOrDigit() || ch == '_' } },
                                        label = { Text("Asset File Name (no extension)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    if (project != null) {
                                        val destFile = File(project.rootPath, "$selectedTargetFolder/$exportFileName.${selectedExportFormat.ext}")
                                        Text(
                                            text = "Destination: $selectedTargetFolder/$exportFileName.${selectedExportFormat.ext}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.Gray
                                        )
                                        Button(
                                            onClick = {
                                                if (exportFileName.isBlank()) {
                                                    Toast.makeText(context, "Enter an asset name", Toast.LENGTH_SHORT).show()
                                                    return@Button
                                                }
                                                val bytes = AssetStudioProcessor.encodeBitmap(bmp, selectedExportFormat)
                                                saveFileSafely(destFile, bytes)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Save Asset to Project", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                // Tab 1: Icon Studio
                val preview192 = remember(iconBgColorInt, iconFgColorInt, iconShape, iconMonogram, iconPadding, useImportedAsIconFg, editedBitmap) {
                    AssetStudioProcessor.generateLauncherIconBitmap(
                        sizePx = 192,
                        backgroundColorInt = iconBgColorInt,
                        foregroundColorInt = iconFgColorInt,
                        shape = iconShape,
                        monogramText = iconMonogram,
                        foregroundBitmap = if (useImportedAsIconFg) editedBitmap else null,
                        paddingRatio = iconPadding
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            border = BorderStroke(1.dp, Slate800)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Multi-Size Launcher Icon Preview", fontWeight = FontWeight.Bold, color = Color.White)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(48 to "mdpi", 72 to "hdpi", 96 to "xhdpi").forEach { (dpSize, label) ->
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Image(
                                                bitmap = preview192.asImageBitmap(),
                                                contentDescription = label,
                                                modifier = Modifier.size(dpSize.dp)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(label, fontSize = 10.sp, color = Color.Gray)
                                        }
                                    }
                                }

                                Text("Icon Shape", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconShapeOption.entries.forEach { opt ->
                                        FilterChip(
                                            selected = iconShape == opt,
                                            onClick = { iconShape = opt },
                                            label = { Text(opt.label) }
                                        )
                                    }
                                }

                                OutlinedTextField(
                                    value = iconMonogram,
                                    onValueChange = { iconMonogram = it.take(3) },
                                    label = { Text("Monogram Text (if no image foreground)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                if (editedBitmap != null) {
                                    FilterChip(
                                        selected = useImportedAsIconFg,
                                        onClick = { useImportedAsIconFg = !useImportedAsIconFg },
                                        label = { Text("Use Imported Image as Foreground") }
                                    )
                                }

                                Text("Padding: ${(iconPadding * 100).toInt()}%", fontSize = 12.sp, color = Color.LightGray)
                                Slider(
                                    value = iconPadding,
                                    onValueChange = { iconPadding = it },
                                    valueRange = 0.05f..0.35f
                                )

                                Text("Background Color Preset", fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    listOf("#0F172A", "#1E1B4B", "#064E3B", "#7C2D12", "#FFFFFF").forEach { hex ->
                                        val cInt = android.graphics.Color.parseColor(hex)
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(Color(cInt))
                                                .border(
                                                    width = if (iconBgColorInt == cInt) 2.5.dp else 1.dp,
                                                    color = if (iconBgColorInt == cInt) NeonCyan else Color.Gray,
                                                    shape = CircleShape
                                                )
                                                .clickable { iconBgColorInt = cInt }
                                        )
                                    }
                                }

                                if (project != null) {
                                    val targetSample = File(project.rootPath, "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
                                    Text(
                                        text = "Destination: app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher*.png",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Gray
                                    )
                                    Button(
                                        onClick = {
                                            val doExport = {
                                                val written = AssetStudioProcessor.exportAndroidLauncherIcons(
                                                    projectRoot = File(project.rootPath),
                                                    backgroundColorInt = iconBgColorInt,
                                                    foregroundColorInt = iconFgColorInt,
                                                    shape = iconShape,
                                                    monogramText = iconMonogram,
                                                    foregroundBitmap = if (useImportedAsIconFg) editedBitmap else null
                                                )
                                                assetsRefreshTrigger++
                                                Toast.makeText(context, "Exported ${written.size} launcher icon files!", Toast.LENGTH_SHORT).show()
                                            }
                                            if (targetSample.exists()) {
                                                pendingOverwriteAction = "Overwrite existing mipmap launcher icons in ${project.name}?" to doExport
                                            } else {
                                                doExport()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Export All Android Mipmap Icons", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                // Tab 2: Splash Screen Designer
                val parsedSplashBg = remember(splashBgHex) {
                    try { Color(android.graphics.Color.parseColor(splashBgHex)) } catch (e: Exception) { Slate950 }
                }
                val parsedSplashFg = remember(splashTextColorHex) {
                    try { Color(android.graphics.Color.parseColor(splashTextColorHex)) } catch (e: Exception) { Color.White }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            border = BorderStroke(1.dp, Slate800)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Splash Screen Live Preview", fontWeight = FontWeight.Bold, color = Color.White)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(220.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(parsedSplashBg)
                                        .border(2.dp, Slate800, RoundedCornerShape(16.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        val logoBmp = editedBitmap
                                        if (logoBmp != null) {
                                            Image(
                                                bitmap = logoBmp.asImageBitmap(),
                                                contentDescription = "Splash Logo",
                                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(14.dp))
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(72.dp)
                                                    .clip(CircleShape)
                                                    .background(NeonCyan.copy(alpha = 0.2f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    splashTitle.take(2).uppercase().ifBlank { "NX" },
                                                    fontSize = 24.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeonCyan
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(splashTitle, color = parsedSplashFg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    }
                                }

                                OutlinedTextField(
                                    value = splashTitle,
                                    onValueChange = { splashTitle = it },
                                    label = { Text("Splash Title") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = splashBgHex,
                                        onValueChange = { splashBgHex = it },
                                        label = { Text("Background HEX") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    OutlinedTextField(
                                        value = splashTextColorHex,
                                        onValueChange = { splashTextColorHex = it },
                                        label = { Text("Text HEX") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                if (project != null) {
                                    val destLogo = File(project.rootPath, "app/src/main/res/drawable/splash_logo.png")
                                    Text(
                                        text = "Exports: res/drawable/splash_logo.png & res/values/splash_colors.xml",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.Gray
                                    )
                                    Button(
                                        onClick = {
                                            val doExport = {
                                                AssetStudioProcessor.exportSplashAssets(
                                                    projectRoot = File(project.rootPath),
                                                    bgColorHex = splashBgHex,
                                                    titleText = splashTitle,
                                                    textColorHex = splashTextColorHex,
                                                    logoBitmap = editedBitmap
                                                )
                                                assetsRefreshTrigger++
                                                Toast.makeText(context, "Splash assets exported!", Toast.LENGTH_SHORT).show()
                                            }
                                            if (destLogo.exists()) {
                                                pendingOverwriteAction = "Overwrite existing splash_logo.png and splash_colors.xml?" to doExport
                                            } else {
                                                doExport()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Export Splash Resources", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            3 -> {
                // Tab 3: Design Tools (Color Picker, HEX/RGB, Palette, Font Preview)
                val currentColor = Color(redVal.toInt(), greenVal.toInt(), blueVal.toInt())
                val currentHex = AssetStudioProcessor.toHex(currentColor.toArgb())
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            border = BorderStroke(1.dp, Slate800)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Color Picker & HEX / RGB Inspector", fontWeight = FontWeight.Bold, color = Color.White)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(currentColor)
                                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(currentHex, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = Color.White, fontSize = 18.sp)
                                        Text("RGB(${redVal.toInt()}, ${greenVal.toInt()}, ${blueVal.toInt()})", fontSize = 12.sp, color = NeonCyan)
                                    }
                                    IconButton(onClick = {
                                        clipboardManager.setText(AnnotatedString(currentHex))
                                        Toast.makeText(context, "Copied $currentHex", Toast.LENGTH_SHORT).show()
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy HEX", tint = NeonCyan)
                                    }
                                }

                                Text("Red: ${redVal.toInt()}", fontSize = 11.sp, color = Color.LightGray)
                                Slider(value = redVal, onValueChange = { redVal = it }, valueRange = 0f..255f)
                                Text("Green: ${greenVal.toInt()}", fontSize = 11.sp, color = Color.LightGray)
                                Slider(value = greenVal, onValueChange = { greenVal = it }, valueRange = 0f..255f)
                                Text("Blue: ${blueVal.toInt()}", fontSize = 11.sp, color = Color.LightGray)
                                Slider(value = blueVal, onValueChange = { blueVal = it }, valueRange = 0f..255f)

                                Button(
                                    onClick = {
                                        if (currentHex !in savedPalette) savedPalette.add(0, currentHex)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Save Swatch to Palette")
                                }

                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    savedPalette.forEach { hex ->
                                        val c = Color(android.graphics.Color.parseColor(hex))
                                        Surface(
                                            color = Slate800,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.clickable {
                                                val argb = android.graphics.Color.parseColor(hex)
                                                redVal = android.graphics.Color.red(argb).toFloat()
                                                greenVal = android.graphics.Color.green(argb).toFloat()
                                                blueVal = android.graphics.Color.blue(argb).toFloat()
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(c))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(hex, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            border = BorderStroke(1.dp, Slate800)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Font & Typography Preview", fontWeight = FontWeight.Bold, color = Color.White)
                                OutlinedTextField(
                                    value = fontPreviewText,
                                    onValueChange = { fontPreviewText = it },
                                    label = { Text("Sample Text") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("Size: ${fontPreviewSize.toInt()} sp", fontSize = 11.sp, color = NeonCyan)
                                Slider(value = fontPreviewSize, onValueChange = { fontPreviewSize = it }, valueRange = 12f..28f)

                                listOf(
                                    "Sans-Serif" to FontFamily.SansSerif,
                                    "Monospace" to FontFamily.Monospace,
                                    "Serif" to FontFamily.Serif
                                ).forEach { (label, family) ->
                                    Column {
                                        Text(label, fontSize = 10.sp, color = Color.Gray)
                                        Text(fontPreviewText, fontFamily = family, fontSize = fontPreviewSize.sp, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            4 -> {
                // Tab 4: Project Visual Assets Browser
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (projectAssets.isEmpty()) {
                        item {
                            Surface(
                                color = Slate900,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "No PNG/JPG/WEBP/SVG assets found in this project yet. Export an icon, splash, or image to see it here.",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    } else {
                        items(projectAssets, key = { it.relativePath }) { asset ->
                            val thumb = remember(asset.absolutePath) {
                                if (asset.extension == "svg") {
                                    AssetStudioProcessor.renderBasicSvgToBitmap(File(asset.absolutePath).readText(), 96, 96)
                                } else {
                                    BitmapFactory.decodeFile(asset.absolutePath)
                                }
                            }
                            Surface(
                                color = Slate900,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Slate800),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (thumb != null) {
                                            editedBitmap = thumb
                                            selectedTab = 0
                                            Toast.makeText(context, "Opened ${asset.name} in Image Tools", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (thumb != null) {
                                        Image(
                                            bitmap = thumb.asImageBitmap(),
                                            contentDescription = asset.name,
                                            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                                        )
                                    } else {
                                        Icon(Icons.Default.Image, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(36.dp))
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(asset.name, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                                        Text(asset.relativePath, fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("${asset.sizeBytes / 1024} KB", fontSize = 11.sp, color = NeonCyan)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingOverwriteAction?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { pendingOverwriteAction = null },
            title = { Text("Confirm File Overwrite", fontWeight = FontWeight.Bold) },
            text = { Text(message, fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        pendingOverwriteAction = null
                        action()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberYellow, contentColor = Slate950)
                ) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOverwriteAction = null }) { Text("Cancel") }
            }
        )
    }
}
