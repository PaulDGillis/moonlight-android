package com.limelight

import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.limelight.preferences.AddComputerManuallyActivity
import com.limelight.preferences.StreamSettings
import com.limelight.utils.HelpLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcGridScreen() {
    Scaffold(
        topBar = {
            val context = LocalContext.current
            TopAppBar(
                navigationIcon = {
                    Row {
                        IconButton(R.drawable.ic_settings, R.string.category_general_settings) {
                            context.startActivity(Intent(context, StreamSettings::class.java))
                        }
                        // Amazon review didn't like the help button because the wiki was not entirely
                        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
                        // it on Fire TV.
                        val isFireTv by rememberSaveable {
                            mutableStateOf(context.packageManager.hasSystemFeature("amazon.hardware.fire_tv"))
                        }
                        if (isFireTv.not()) {
                            IconButton(R.drawable.ic_help, R.string.category_help) {
                                HelpLauncher.launchSetupGuide(context)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(R.drawable.ic_add, R.string.title_add_pc) {
                        context.startActivity(Intent(context, AddComputerManuallyActivity::class.java))
                    }
                },
                title = {},
            )
        }
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            NoPcFoundLoading()
        }
    }
}

@Composable
private fun NoPcFoundLoading(
    modifier: Modifier = Modifier
) {
    Row(
        modifier.padding(horizontal = dimensionResource(R.dimen.activity_horizontal_margin)),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(Modifier.size(75.dp, 75.dp))
        Text(
            text = stringResource(R.string.searching_pc),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PcIcon(
    isLoading: Boolean,

) {
    Column(
        Modifier.padding(20.dp).width(125.dp).wrapContentHeight(),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(125.dp)) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                Icon(painterResource(R.drawable.ic_computer), null)
            }
        }

        Text(
            "DESKTOP-PAUL",
            Modifier.wrapContentSize(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun IconButton(
    @DrawableRes painterRes: Int,
    @StringRes contentDescriptionRes: Int,
    onClick: () -> Unit
) {
    IconButton(onClick, Modifier.size(70.dp, 65.dp)) {
        Icon(painterResource(painterRes), stringResource(contentDescriptionRes))
    }
}

@Preview
@Composable
private fun PcIconPreview() {
    MaterialTheme {
        PcIcon(isLoading = false)
    }
}

@Preview
@Composable
private fun PcGridPreview() {
    MaterialTheme {
        PcGridScreen()
    }
}