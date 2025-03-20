package com.limelight

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.limelight.computers.ComputerManagerService
import org.bouncycastle.pqc.legacy.math.linearalgebra.IntegerFunctions.mod
import org.koin.androidx.compose.koinViewModel

@Composable
fun GameSelectScreen(
    viewModel: AppSelectViewModel = koinViewModel()
) {
    rememberBoundService<ComputerManagerService, ComputerManagerService.ComputerManagerBinder>(
        onServiceConnected = viewModel::onComputerServiceConnected,
        onServiceDisconnected = viewModel::onComputerServiceDisconnected
    )
    GameSelectScreen(title = "")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameSelectScreen(
    modifier: Modifier = Modifier,
    title: String
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = {
                Text(title, style = MaterialTheme.typography.titleLarge)
            })
        }
    ) { contentPadding ->
        LazyVerticalGrid(
            GridCells.Adaptive(128.dp),
            modifier.padding(contentPadding).fillMaxSize()
        ) {

        }
    }
}

@Composable
fun AppImageCard(
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    Card(
        modifier = modifier.padding(5.dp)
            .size(100.dp, 133.dp),
        shape = RoundedCornerShape(6.dp),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
//            AsyncImage(
//                contentDescription = "",
//                modifier = Modifier.fillMaxSize(),
//                contentScale = ContentScale.Crop,
//                clipToBounds = false
//            )
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
            ) {
//                Image()
                Text(
                    "",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Preview
@Composable
fun GameSelectScreenPreview() {
    GameSelectScreen(title = "Test")
}