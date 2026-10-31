package app.vitaflow.com.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vitaflow.com.ui.theme.GlassButton
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.LocalGlass

/** Glass-style pop-up with a title and any content. */
@Composable
fun GlassDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val g = LocalGlass.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassSurface(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, color = g.textPrimary, style = MaterialTheme.typography.titleLarge)
                content()
            }
        }
    }
}

/** Every delete asks first. */
@Composable
fun ConfirmDialog(message: String, confirmLabel: String = "মুছে ফেলুন", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val g = LocalGlass.current
    GlassDialog("নিশ্চিত করুন", onDismiss) {
        Text(message, color = g.textSecondary, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("না", onDismiss, Modifier.weight(1f))
            GlassButton(confirmLabel, { onConfirm(); onDismiss() }, Modifier.weight(1f), danger = true)
        }
    }
}
