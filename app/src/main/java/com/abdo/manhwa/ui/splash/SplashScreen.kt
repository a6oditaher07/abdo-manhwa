package com.abdo.manhwa.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abdo.manhwa.R
import com.abdo.manhwa.ui.theme.AccentPrimary
import com.abdo.manhwa.ui.theme.BackgroundDark
import kotlinx.coroutines.delay

/** شاشة بداية أنيقة بشعار واسم التطبيق تظهر قبل الانتقال إلى المكتبة */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val alpha = Animatable(0f)
    val scale = Animatable(0.85f)

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(600))
        scale.animateTo(1f, animationSpec = androidx.compose.animation.core.tween(600))
        delay(650)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(alpha.value).scale(scale.value)
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(AccentPrimary, shape = RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("AM", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "ABDO MANHWA",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "مكتبتك الخاصة لقراءة المانهوا",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
