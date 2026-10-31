package app.vitaflow.com.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Gradient sky with two soft blurred glows. The glass surfaces sit on top of this. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val g = LocalGlass.current
    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(g.bgTop, g.bgBottom)))) {
        if (!LocalReduceEffects.current) {
            Canvas(Modifier.fillMaxSize().blur(70.dp)) {
                drawCircle(g.blobA, radius = size.minDimension * 0.55f, center = Offset(size.width * 0.10f, size.height * 0.10f))
                drawCircle(g.blobB, radius = size.minDimension * 0.50f, center = Offset(size.width * 0.95f, size.height * 0.80f))
            }
        }
        content()
    }
}

/** A translucent panel with a light edge on top, like frosted glass. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val g = LocalGlass.current
    val reduce = LocalReduceEffects.current
    val fill = if (reduce) androidx.compose.ui.graphics.lerp(g.bgBottom, g.textPrimary, 0.08f) else g.glassFill
    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .background(Brush.verticalGradient(listOf(if (reduce) Color.Transparent else g.glassHighlight, Color.Transparent)))
            .border(1.dp, Brush.verticalGradient(listOf(g.borderTop, g.borderBottom)), shape),
        content = content
    )
}

@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false
) {
    val g = LocalGlass.current
    val tint = if (danger) g.danger else g.accent
    val shape = RoundedCornerShape(18.dp)
    val top = if (enabled) g.buttonAlphaTop else 0.18f
    val bottom = if (enabled) g.buttonAlphaBottom else 0.10f
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = top), tint.copy(alpha = bottom))))
            .border(1.dp, Brush.verticalGradient(listOf(g.borderTop, g.borderBottom)), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (enabled) Color.White else g.textSecondary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

/** Small selectable pill, used for choices like the theme mode. */
@Composable
fun GlassChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) g.accent.copy(alpha = 0.40f) else g.glassFill)
            .border(
                1.dp,
                if (selected) Brush.verticalGradient(listOf(g.accent, g.accent))
                else Brush.verticalGradient(listOf(g.borderTop, g.borderBottom)),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = g.textPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val g = LocalGlass.current
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                errorIndicatorColor = Color.Transparent,
                focusedTextColor = g.textPrimary,
                unfocusedTextColor = g.textPrimary,
                focusedLabelColor = g.accent,
                unfocusedLabelColor = g.textSecondary,
                cursorColor = g.accent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
