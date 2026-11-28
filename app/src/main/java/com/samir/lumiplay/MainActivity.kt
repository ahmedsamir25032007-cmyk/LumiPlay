package com.samir.lumiplay

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StreamSession(
    val title: String,
    val servers: List<AnimeSaltServer>,
    val initialIndex: Int = 0
)

data class LumiUiState(
    val showWelcome: Boolean = true,
    val selectedCategory: AnimeCategory = AnimeCategory.HOME,
    val animeItems: List<AnimeSaltItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val searchQuery: String = "",
    val activeQuery: String = "",
    // Details
    val selectedAnime: AnimeSaltItem? = null,
    val animeDetails: AnimeSaltDetails? = null,
    val selectedSeason: Int = 1,
    val isDetailsLoading: Boolean = false,
    val detailsError: String? = null,
    val isLoadingStream: Boolean = false,
    // Active Player
    val activeStream: StreamSession? = null
)

class LumiViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(LumiUiState(isLoading = true))
    val uiState: StateFlow<LumiUiState> = _uiState.asStateFlow()

    init {
        loadCategory(AnimeCategory.HOME)
        viewModelScope.launch {
            delay(1800)
            if (_uiState.value.showWelcome) {
                _uiState.value = _uiState.value.copy(showWelcome = false)
            }
        }
    }

    fun enterAppNow() {
        _uiState.value = _uiState.value.copy(showWelcome = false)
    }

    fun onQueryChange(newQuery: String) {
        _uiState.value = _uiState.value.copy(searchQuery = newQuery)
    }

    fun loadCategory(category: AnimeCategory = _uiState.value.selectedCategory) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                selectedCategory = category,
                isLoading = true,
                errorMessage = null,
                activeQuery = ""
            )
            try {
                val list = AnimeSaltScraper.fetchCategory(category)
                _uiState.value = _uiState.value.copy(
                    animeItems = list,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "ডেটা লোড করা যায়নি। ইন্টারনেট চেক করে আবার চেষ্টা করো।"
                )
            }
        }
    }

    fun search(queryText: String = _uiState.value.searchQuery) {
        val trimmed = queryText.trim()
        if (trimmed.isEmpty()) {
            loadCategory(_uiState.value.selectedCategory)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                activeQuery = trimmed
            )
            try {
                val results = AnimeSaltScraper.search(trimmed)
                _uiState.value = _uiState.value.copy(
                    animeItems = results,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "সার্চ করা যায়নি। আবার চেষ্টা করো।"
                )
            }
        }
    }

    fun openAnimeDetails(item: AnimeSaltItem) {
        if (item.type == "episode") {
            playEpisodeDirectly(item.title, item.url)
            return
        }
        _uiState.value = _uiState.value.copy(
            selectedAnime = item,
            animeDetails = null,
            isDetailsLoading = true,
            detailsError = null
        )
        viewModelScope.launch {
            try {
                val details = AnimeSaltScraper.fetchDetails(item.url, item.slug)
                val firstSeason = details.seasons.keys.firstOrNull() ?: 1
                _uiState.value = _uiState.value.copy(
                    animeDetails = details,
                    selectedSeason = firstSeason,
                    isDetailsLoading = false
                )
                if (details.seasons.isEmpty()) {
                    playEpisodeDirectly(item.title, item.url)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDetailsLoading = false,
                    detailsError = "এপিসোড লিস্ট লোড করা যায়নি।"
                )
            }
        }
    }

    fun selectSeason(season: Int) {
        _uiState.value = _uiState.value.copy(selectedSeason = season)
    }

    fun playEpisodeDirectly(epTitle: String, epUrl: String) {
        _uiState.value = _uiState.value.copy(isLoadingStream = true, detailsError = null)
        viewModelScope.launch {
            try {
                val servers = AnimeSaltScraper.fetchEpisodeStreams(epUrl)
                if (servers.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoadingStream = false,
                        detailsError = "কোনো ভিডিও সার্ভার পাওয়া যায়নি।"
                    )
                } else {
                    val hindiIdx = servers.indexOfFirst { it.name.contains("Hindi", ignoreCase = true) }
                    val startIdx = if (hindiIdx >= 0) hindiIdx else 0
                    _uiState.value = _uiState.value.copy(
                        isLoadingStream = false,
                        activeStream = StreamSession(
                            title = epTitle,
                            servers = servers,
                            initialIndex = startIdx
                        )
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingStream = false,
                    detailsError = "ভিডিও লিংক লোড করতে সমস্যা হয়েছে।"
                )
            }
        }
    }

    fun closeAnimeDetails() {
        _uiState.value = _uiState.value.copy(
            selectedAnime = null,
            animeDetails = null,
            isDetailsLoading = false,
            detailsError = null
        )
    }

    fun closeStream() {
        _uiState.value = _uiState.value.copy(activeStream = null)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LumiPlayRoot()
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun LiquidBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070913))
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x664F46E5), Color.Transparent),
                    center = Offset(180f, 260f),
                    radius = 780f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x5506B6D4), Color.Transparent),
                    center = Offset(920f, 1550f),
                    radius = 880f
                )
            ),
        content = content
    )
}

@Composable
fun LumiPlayRoot(lumiViewModel: LumiViewModel = viewModel()) {
    val state by lumiViewModel.uiState.collectAsState()

    AnimatedContent(
        targetState = state.showWelcome,
        transitionSpec = {
            fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(300))
        },
        label = "ScreenTransition"
    ) { isWelcome ->
        if (isWelcome) {
            LumiPlayWelcomeScreen(onContinue = { lumiViewModel.enterAppNow() })
        } else {
            LumiPlayHomeScreen(state = state, lumiViewModel = lumiViewModel)
        }
    }

    val streamSession = state.activeStream
    if (streamSession != null) {
        ProAnimePlayerDialog(
            session = streamSession,
            onDismiss = { lumiViewModel.closeStream() }
        )
    }
}

@Composable
fun LumiPlayWelcomeScreen(onContinue: () -> Unit) {
    LiquidBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.15f),
                                Color.White.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.38f),
                                Color.Cyan.copy(alpha = 0.18f)
                            )
                        ),
                        shape = RoundedCornerShape(28.dp)
                    )
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "LumiPlay",
                    color = Color.White,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Premium Anime Cinema",
                    color = Color(0xFF93C5FD),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(22.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x3322D3EE))
                        .border(1.dp, Color(0x6622D3EE), RoundedCornerShape(50))
                        .clickable { onContinue() }
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "Enter Anime World",
                        color = Color(0xFF67E8F9),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Text(
                text = "Samir Ahmed",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
    }
}

@Composable
fun LumiPlayHomeScreen(
    state: LumiUiState,
    lumiViewModel: LumiViewModel
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    LiquidBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                Column(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "LumiPlay Anime",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (state.activeQuery.isEmpty())
                                    "${state.selectedCategory.label} • Hindi & Multi Dub"
                                else
                                    "Search: ${state.activeQuery}",
                                color = Color(0xFF93C5FD),
                                fontSize = 12.sp
                            )
                        }

                        if (state.activeQuery.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    lumiViewModel.onQueryChange("")
                                    lumiViewModel.loadCategory(state.selectedCategory)
                                }
                            ) {
                                Text("Reset", color = Color(0xFF67E8F9))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { lumiViewModel.onQueryChange(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.30f),
                                        Color.Cyan.copy(alpha = 0.20f)
                                    )
                                ),
                                shape = RoundedCornerShape(18.dp)
                            ),
                        placeholder = {
                            Text(
                                text = "Search anime (Naruto, Solo Leveling...)",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 13.sp
                            )
                        },
                        leadingIcon = {
                            IconButton(onClick = {
                                keyboardController?.hide()
                                lumiViewModel.search()
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = "Search",
                                    tint = Color(0xFF67E8F9)
                                )
                            }
                        },
                        trailingIcon = {
                            if (state.searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    lumiViewModel.onQueryChange("")
                                    lumiViewModel.loadCategory(state.selectedCategory)
                                }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Clear,
                                        contentDescription = "Clear",
                                        tint = Color.White.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboardController?.hide()
                                lumiViewModel.search()
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFF67E8F9),
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(18.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(AnimeCategory.entries) { category ->
                            val isSelected = state.activeQuery.isEmpty() && state.selectedCategory == category
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        if (isSelected) Color(0x3322D3EE) else Color.White.copy(alpha = 0.07f)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0x9922D3EE) else Color.White.copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(50)
                                    )
                                    .clickable {
                                        lumiViewModel.onQueryChange("")
                                        lumiViewModel.loadCategory(category)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = category.label,
                                    color = if (isSelected) Color(0xFF67E8F9) else Color.White.copy(alpha = 0.82f),
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp)
            ) {
                when {
                    state.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color(0xFF67E8F9)
                        )
                    }

                    state.errorMessage != null -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = state.errorMessage,
                                color = Color(0xFFFCA5A5),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { lumiViewModel.loadCategory(state.selectedCategory) }) {
                                Text("Try Again")
                            }
                        }
                    }

                    state.animeItems.isEmpty() -> {
                        Text(
                            text = "কোনো অ্যানিমে পাওয়া যায়নি।",
                            color = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(state.animeItems) { anime ->
                                LiquidAnimeCard(
                                    item = anime,
                                    onClick = { lumiViewModel.openAnimeDetails(anime) }
                                )
                            }
                        }
                    }
                }

                if (state.isLoadingStream) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x88000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color(0xFF67E8F9))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("ভিডিও সার্ভার লোড হচ্ছে...", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        val selectedAnime = state.selectedAnime
        if (selectedAnime != null) {
            SeasonEpisodeDialog(
                item = selectedAnime,
                details = state.animeDetails,
                selectedSeason = state.selectedSeason,
                isLoading = state.isDetailsLoading,
                errorText = state.detailsError,
                onSelectSeason = { lumiViewModel.selectSeason(it) },
                onPlayEpisode = { ep ->
                    val fullTitle = "${selectedAnime.title} • S${ep.season} E${ep.episode}"
                    lumiViewModel.playEpisodeDirectly(fullTitle, ep.url)
                },
                onDismiss = { lumiViewModel.closeAnimeDetails() }
            )
        }
    }
}

@Composable
fun LiquidAnimeCard(
    item: AnimeSaltItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.13f),
                        Color.White.copy(alpha = 0.04f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.28f),
                        Color.Cyan.copy(alpha = 0.12f)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center
        ) {
            if (item.poster.isNotBlank()) {
                AsyncImage(
                    model = item.poster,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(36.dp)
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xCC070913))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = item.type.uppercase(),
                    color = Color(0xFF67E8F9),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = item.title.ifBlank { "Untitled" },
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun SeasonEpisodeDialog(
    item: AnimeSaltItem,
    details: AnimeSaltDetails?,
    selectedSeason: Int,
    isLoading: Boolean,
    errorText: String?,
    onSelectSeason: (Int) -> Unit,
    onPlayEpisode: (AnimeSaltEpisode) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 660.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF0C1222))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.34f),
                            Color.Cyan.copy(alpha = 0.22f)
                        )
                    ),
                    shape = RoundedCornerShape(26.dp)
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val poster = details?.poster?.takeIf { it.isNotBlank() } ?: item.poster
                if (poster.isNotBlank()) {
                    AsyncImage(
                        model = poster,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(64.dp)
                            .height(92.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = details?.title?.takeIf { it.isNotBlank() } ?: item.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Hindi / Multi Audio",
                        color = Color(0xFF67E8F9),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            if (!details?.overview.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = details!!.overview,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF67E8F9))
                }
            } else {
                if (errorText != null) {
                    Text(text = errorText, color = Color(0xFFFCA5A5), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                }

                val seasonsMap = details?.seasons.orEmpty()
                if (seasonsMap.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(seasonsMap.keys.toList()) { seasonNum ->
                            val active = seasonNum == selectedSeason
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (active) Color(0x3322D3EE) else Color.White.copy(alpha = 0.08f))
                                    .border(
                                        1.dp,
                                        if (active) Color(0x9922D3EE) else Color.White.copy(alpha = 0.15f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { onSelectSeason(seasonNum) }
                                    .padding(horizontal = 14.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = "Season $seasonNum",
                                    color = if (active) Color(0xFF67E8F9) else Color.White.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val currentEpisodes = seasonsMap[selectedSeason].orEmpty()
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        items(currentEpisodes) { ep ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                                    .clickable { onPlayEpisode(ep) }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = Color(0xFF67E8F9),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "EP ${ep.episode}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ProAnimePlayerDialog(
    session: StreamSession,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var selectedServerIdx by remember(session) { mutableIntStateOf(session.initialIndex) }
    val currentServer = session.servers.getOrNull(selectedServerIdx) ?: session.servers.first()

    var directVideoUrl by remember(currentServer.url) { mutableStateOf<String?>(null) }
    var useExoPlayer by remember(currentServer.url) { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(false) }
    var showControlsPanel by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    val maxVol = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volumeLevel by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVol.toFloat())
    }
    var brightnessLevel by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun toggleOrientation() {
        isLandscape = !isLandscape
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun looksLikeDirectStream(raw: String?): Boolean {
        val u = raw?.lowercase().orEmpty()
        return u.contains(".m3u8") || u.contains(".mp4")
    }

    val blockedKeywords = remember {
        listOf(
            "casino", "bet", "poker", "slot", "adsterra", "propeller",
            "exoclick", "juicyads", "popads", "hilltopads", "clickadu",
            "doubleclick", "googlesyndication", "redirect"
        )
    }

    val exoPlayer = remember(directVideoUrl, useExoPlayer) {
        val target = directVideoUrl
        if (useExoPlayer && !target.isNullOrBlank()) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(target))
                playbackParameters = PlaybackParameters(playbackSpeed)
                prepare()
                playWhenReady = true
            }
        } else {
            null
        }
    }

    LaunchedEffect(playbackSpeed, exoPlayer) {
        exoPlayer?.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer?.release()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF05070E))
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0E1426))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 6.dp)) {
                    Text(
                        text = session.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${currentServer.name} • ${if (useExoPlayer) "Native HD Player" else "In-App Stream"}",
                        color = Color(0xFF67E8F9),
                        fontSize = 11.sp
                    )
                }

                IconButton(onClick = { showControlsPanel = !showControlsPanel }) {
                    Icon(
                        imageVector = Icons.Rounded.Brightness6,
                        contentDescription = "Controls",
                        tint = if (showControlsPanel) Color(0xFF67E8F9) else Color.White
                    )
                }

                IconButton(onClick = { toggleOrientation() }) {
                    Icon(
                        imageVector = Icons.Rounded.ScreenRotation,
                        contentDescription = "Rotate",
                        tint = Color.White
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }
            }

            // In-Player Dub / Server Switcher Bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0F1E))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(session.servers.indices.toList()) { idx ->
                    val srv = session.servers[idx]
                    val active = idx == selectedServerIdx
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (active) Color(0x3322D3EE) else Color.White.copy(alpha = 0.08f))
                            .border(
                                1.dp,
                                if (active) Color(0x9922D3EE) else Color.White.copy(alpha = 0.16f),
                                RoundedCornerShape(50)
                            )
                            .clickable {
                                selectedServerIdx = idx
                                directVideoUrl = null
                                useExoPlayer = false
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = srv.name,
                            color = if (active) Color(0xFF67E8F9) else Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            // Brightness, Volume & Speed Control Panel
            if (showControlsPanel) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF11182C))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Brightness6,
                            contentDescription = null,
                            tint = Color(0xFFFDE68A),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(
                            value = brightnessLevel,
                            onValueChange = { v ->
                                brightnessLevel = v
                                activity?.window?.let { win ->
                                    val lp = win.attributes
                                    lp.screenBrightness = v.coerceIn(0.05f, 1f)
                                    win.attributes = lp
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.VolumeUp,
                            contentDescription = null,
                            tint = Color(0xFF67E8F9),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Slider(
                            value = volumeLevel,
                            onValueChange = { v ->
                                volumeLevel = v
                                val targetVol = (v * maxVol).toInt().coerceIn(0, maxVol)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (useExoPlayer) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = null,
                                tint = Color(0xFF93C5FD),
                                modifier = Modifier.size(18.dp)
                            )
                            listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                val active = playbackSpeed == spd
                                Text(
                                    text = "${spd}x",
                                    color = if (active) Color(0xFF67E8F9) else Color.White.copy(alpha = 0.75f),
                                    fontSize = 12.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (active) Color(0x3322D3EE) else Color.Transparent)
                                        .clickable { playbackSpeed = spd }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Video Player Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
            ) {
                if (useExoPlayer && exoPlayer != null) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = true
                                controllerShowTimeoutMs = 3500
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    key(currentServer.url) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.setSupportMultipleWindows(false)
                                    settings.javaScriptCanOpenWindowsAutomatically = false
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    settings.userAgentString =
                                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                                    webChromeClient = WebChromeClient()
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val rawUrl = request?.url?.toString().orEmpty()
                                            val lower = rawUrl.lowercase()
                                            if (blockedKeywords.any { key -> lower.contains(key) }) {
                                                Toast.makeText(ctx, "Ad blocked", Toast.LENGTH_SHORT).show()
                                                return true
                                            }
                                            return false
                                        }

                                        override fun onLoadResource(view: WebView?, url: String?) {
                                            super.onLoadResource(view, url)
                                            if (!useExoPlayer && looksLikeDirectStream(url) && url != null) {
                                                directVideoUrl = url
                                                useExoPlayer = true
                                            }
                                        }
                                    }
                                    loadUrl(
                                        currentServer.url,
                                        mapOf("Referer" to "${AnimeSaltScraper.BASE_URL}/")
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
