package com.gmdparser.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gmdparser.ui.theme.*
import com.gmdparser.util.AppPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * PIN entry screen shown at app launch when a PIN is configured.
 * Also used from Settings to set/change/remove a PIN.
 *
 * @param mode UNLOCK (verify existing), SET (choose new), CONFIRM (re-enter new)
 */
@Composable
fun PinLockScreen(
    mode: PinScreenMode = PinScreenMode.UNLOCK,
    pendingPin: String? = null,           // passed in CONFIRM mode
    onUnlocked: () -> Unit = {},
    onPinSet: (String) -> Unit = {},      // returns plaintext pin for CONFIRM hash
    onCancel: (() -> Unit)? = null
) {
    var entered by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var shakeOffset by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    val maxLen = 6

    fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun handleComplete(pin: String) {
        when (mode) {
            PinScreenMode.UNLOCK -> {
                if (sha256(pin) == AppPreferences.storedPinHash) {
                    onUnlocked()
                } else {
                    scope.launch {
                        errorMessage = "Incorrect PIN. Try again."
                        entered = ""
                        shakeOffset = 12f
                        delay(400)
                        shakeOffset = 0f
                    }
                }
            }
            PinScreenMode.SET -> {
                onPinSet(pin)      // parent will navigate to CONFIRM
            }
            PinScreenMode.CONFIRM -> {
                if (pin == pendingPin) {
                    AppPreferences.savePin(sha256(pin))
                    onUnlocked()   // re-used as "done" callback
                } else {
                    scope.launch {
                        errorMessage = "PINs don't match. Try again."
                        entered = ""
                        shakeOffset = 12f
                        delay(400)
                        shakeOffset = 0f
                    }
                }
            }
        }
    }

    fun onKey(digit: String) {
        if (entered.length < maxLen) {
            entered += digit
            errorMessage = null
            if (entered.length == maxLen) handleComplete(entered)
        }
    }

    fun onBack() {
        if (entered.isNotEmpty()) entered = entered.dropLast(1)
    }

    val titleText = when (mode) {
        PinScreenMode.UNLOCK  -> "Enter PIN"
        PinScreenMode.SET     -> "Set a new PIN"
        PinScreenMode.CONFIRM -> "Confirm PIN"
    }
    val subtitleText = when (mode) {
        PinScreenMode.UNLOCK  -> "Enter your ${maxLen}-digit PIN to unlock GMDParser"
        PinScreenMode.SET     -> "Choose a ${maxLen}-digit PIN to protect your data"
        PinScreenMode.CONFIRM -> "Re-enter the PIN you just set"
    }

    val animatedOffset by animateFloatAsState(
        targetValue = shakeOffset,
        animationSpec = spring(dampingRatio = 0.3f, stiffness = 400f),
        label = "shake"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            // Lock icon header
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = titleText,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitleText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            // PIN dot indicators
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.offset(x = animatedOffset.dp)
            ) {
                repeat(maxLen) { i ->
                    val filled = i < entered.length
                    val dotColor by animateColorAsState(
                        targetValue = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        label = "dot_$i"
                    )
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(dotColor, CircleShape)
                            .border(1.dp, if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    )
                }
            }

            // Error
            if (errorMessage != null) {
                Text(
                    text = errorMessage!!,
                    color = AccentRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                Spacer(Modifier.height(16.dp))
            }

            // Number pad
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("", "0", "⌫")
            )

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                keys.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        row.forEach { key ->
                            PinKey(
                                label = key,
                                onTap = {
                                    if (key == "⌫") onBack() else if (key.isNotEmpty()) onKey(key)
                                }
                            )
                        }
                    }
                }
            }

            // Cancel (only available in SET mode or if a cancel callback is provided)
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PinKey(
    label: String,
    onTap: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
        label = "key_scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .scale(scale)
            .background(
                if (label.isEmpty()) Color.Transparent else MaterialTheme.colorScheme.surface,
                CircleShape
            )
            .then(
                if (label.isNotEmpty())
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                else Modifier
            )
            .clickable(enabled = label.isNotEmpty()) {
                onTap()
            }
    ) {
        if (label == "⌫") {
            Icon(Icons.Default.Backspace, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        } else if (label.isNotEmpty()) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

enum class PinScreenMode { UNLOCK, SET, CONFIRM }
