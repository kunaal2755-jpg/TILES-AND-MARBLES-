package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.firebase.AppLanguage
import com.example.ui.theme.*
import com.example.utils.ImageUtils

@Composable
fun NavyTopHeader(
    title: String,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    language: AppLanguage = AppLanguage.EN,
    onToggleLanguage: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        color = Color.Transparent,
        contentColor = Color.White,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(DeepTealDark, RoyalNavy, SecondaryNavy)
                    )
                )
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onBack != null) {
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(onClick = onBack)
                            .testTag("header_back_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = AmberGold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (onToggleLanguage != null) {
                    Surface(
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, AmberGold.copy(alpha = 0.55f)),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onToggleLanguage)
                            .testTag("language_toggle_button")
                    ) {
                        Text(
                            text = if (language == AppLanguage.EN) "EN / हिं" else "हिं / EN",
                            style = MaterialTheme.typography.labelMedium,
                            color = AmberGold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                actions()
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(AmberGold, AmberGold.copy(alpha = 0.45f), Color.Transparent)
                        )
                    )
            )
        }
    }
}

@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp),
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun KpiTileCard(
    title: String,
    value: String,
    icon: ImageVector,
    accentColor: Color = RoyalNavy,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardWhite),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp),
        border = BorderStroke(1.dp, BorderLight)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(accentColor.copy(alpha = 0.05f), CardWhite)
                    )
                )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentColor.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, contentDescription = title, tint = accentColor, modifier = Modifier.size(20.dp))
                    }
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.7f))
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = SlateDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    color = SlateMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun StatusBadge(text: String, bgColor: Color, textColor: Color) {
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(50),
        border = BorderStroke(0.8.dp, textColor.copy(alpha = 0.22f)),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = textColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun TileThumbnail(
    photoBase64: String,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val bmp = remember(photoBase64) { ImageUtils.decodeBase64Bitmap(photoBase64) }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(12.dp))
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.img_sample_marble),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(12.dp))
        )
    }
}

@Composable
fun EmptyStateView(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_empty_state),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width = 210.dp, height = 150.dp)
                .clip(RoundedCornerShape(18.dp))
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = RoyalNavy
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = SlateMuted
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = RoyalNavy),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
fun FormInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null
) {
    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        enabled = enabled,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null, tint = RoyalNavy) } },
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = SlateMuted
                    )
                }
            }
        } else null,
        visualTransformation = if (isPassword && !passwordVisible) {
            PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = RoyalNavy,
            unfocusedBorderColor = BorderLight,
            focusedContainerColor = CardWhite,
            unfocusedContainerColor = SurfaceElevated
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    confirmColor: Color = DangerRed,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardWhite,
        shape = RoundedCornerShape(22.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge, color = RoyalNavy) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium, color = SlateDark) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = confirmColor),
                shape = RoundedCornerShape(12.dp)
            ) { Text(confirmText) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SectionHeaderRow(
    title: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .clip(CircleShape)
                    .background(AmberGold)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = RoyalNavy
            )
        }
        if (actionText != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionText, color = AmberGoldDark, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
