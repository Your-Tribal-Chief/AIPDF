package com.allpdf.pro.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.allpdf.pro.core.humanSize
import java.io.File

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) },
                leadingIcon = if (o == selected) ({ Icon(Icons.Filled.Check, null, Modifier.size(16.dp)) }) else null)
        }
    }
}

@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, format: (Float) -> String = { it.toInt().toString() }, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, password: Boolean = false, number: Boolean = false, singleLine: Boolean = true, minLines: Int = 1) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, minLines = minLines,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when { password -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Text }),
        trailingIcon = if (password) ({ IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null) } }) else null,
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
        if (icon != null) { Icon(icon, null); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ColorDots(selected: Int, onSelect: (Int) -> Unit, colors: List<Int> = listOf(
    0xFF000000.toInt(), 0xFFE5322D.toInt(), 0xFF2563EB.toInt(), 0xFF16A34A.toInt(), 0xFFF59E0B.toInt(), 0xFF7C3AED.toInt(), 0xFF6B7280.toInt(), 0xFFFFFFFF.toInt())) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { c ->
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c))
                .border(if (c == selected) 3.dp else 1.dp, if (c == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                .clickable { onSelect(c) })
        }
    }
}

@Composable
fun ToolIcon(tool: Tool, size: Int = 44) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 3.5).dp)).background(tool.color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(tool.icon, null, tint = tool.color, modifier = Modifier.size((size * 0.55).dp))
    }
}

@Composable
fun FileRow(f: File, index: Int? = null, onRemove: (() -> Unit)? = null, onUp: (() -> Unit)? = null, onDown: (() -> Unit)? = null) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (index != null) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(10.dp))
            }
            Icon(iconFor(f), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(humanSize(f.length()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onUp != null) IconButton(onClick = onUp) { Icon(Icons.Filled.KeyboardArrowUp, "Move up") }
            if (onDown != null) IconButton(onClick = onDown) { Icon(Icons.Filled.KeyboardArrowDown, "Move down") }
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove") }
        }
    }
}

fun iconFor(f: File): ImageVector = when (f.extension.lowercase()) {
    "pdf" -> Icons.Filled.PictureAsPdf
    "jpg", "jpeg", "png", "webp", "gif" -> Icons.Filled.Image
    "docx", "doc" -> Icons.Filled.Description
    "pptx", "ppt" -> Icons.Filled.Slideshow
    "xlsx", "xls", "csv" -> Icons.Filled.TableChart
    "zip" -> Icons.Filled.FolderZip
    "md", "txt" -> Icons.Filled.Notes
    else -> Icons.Filled.InsertDriveFile
}

/** Big friendly "select files" card. */
@Composable
fun PickCard(tool: Tool, busy: Boolean, onPick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        ToolIcon(tool, 84)
        Spacer(Modifier.height(16.dp))
        Text(tool.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(tool.desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp))
        Surface(onClick = onPick, shape = RoundedCornerShape(22.dp), color = tool.color.copy(alpha = 0.08f),
            border = BorderStroke(2.dp, tool.color.copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth().height(170.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (busy) CircularProgressIndicator(color = tool.color) else Icon(Icons.Filled.UploadFile, null, tint = tool.color, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp))
                Text(when (tool.input) {
                    Input.PDFS -> "Select PDF files"; Input.TWO_PDFS -> "Select two PDFs"; Input.IMAGES -> "Select images"
                    Input.PDF_OR_IMAGES -> "Select a PDF or images"; Input.WORD -> "Select a Word file"; Input.PPT -> "Select a PowerPoint file"
                    Input.EXCEL -> "Select a spreadsheet"; else -> "Select PDF file"
                }, style = MaterialTheme.typography.titleMedium, color = tool.color)
                Text("Processed 100% on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ProgressPanel(tool: Tool, progress: Float, message: String, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(150.dp), strokeWidth = 10.dp, color = tool.color, trackColor = tool.color.copy(alpha = 0.15f))
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
        Text("Running on your phone's CPU — nothing is uploaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Filled.Close, null); Spacer(Modifier.width(6.dp)); Text("Cancel") }
    }
}

@Composable
fun PasswordDialog(name: String, error: String?, onSubmit: (String) -> Unit, onSkip: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onSkip,
        icon = { Icon(Icons.Filled.Lock, null) },
        title = { Text("Password required") },
        text = {
            Column {
                Text("\"$name\" is protected. Enter its password to continue.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Field(pw, { pw = it }, "Password", password = true)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(pw) }) { Text("Unlock") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip file") } },
    )
}

@Composable
fun ScrollBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), content = content)
}

@Composable
fun InfoCard(text: String, icon: ImageVector = Icons.Filled.Info) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
