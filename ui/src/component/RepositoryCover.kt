package moe.yumeyuka.yumehub.ui.component

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.github.panpf.sketch.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle
import moe.yumeyuka.yumehub.data.repository.ReleasePreviewImages
import moe.yumeyuka.yumehub.ui.component.placeholders.AnimatedCardField
import org.koin.compose.koinInject

internal val RepositoryCoverShape = ContinuousRoundedRectangle(12.dp)

@Composable
fun RepositoryCover(
    imageUrl: String?,
    description: String? = null,
    modifier: Modifier = Modifier,
) {
    val images: ReleasePreviewImages = koinInject()
    val url = remember(images, imageUrl) { images.trustedUrl(imageUrl) }
    AnimatedCardField(url) { trustedUrl ->
        AsyncImage(
            uri = trustedUrl,
            contentDescription = description,
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(bottom = ActivityCardSpacing)
                    .aspectRatio(1.91f)
                    .clip(RepositoryCoverShape),
            contentScale = ContentScale.Crop,
        )
    }
}
