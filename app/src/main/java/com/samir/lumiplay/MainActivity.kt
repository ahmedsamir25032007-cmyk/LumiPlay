package com.samir.lumiplay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class MovieItem(
    val title: String? = null,
    val link: String? = null,
    val poster: String? = null
)

data class MoviesResponse(
    val movies: List<MovieItem> = emptyList()
)

data class LinkEntry(
    val label: String? = null,
    val quality: String? = null,
    val url: String? = null,
    val link: String? = null
) {
    fun resolvedUrl(): String? = url ?: link
    fun resolvedTitle(): String =
        listOfNotNull(quality?.takeIf { it.isNotBlank() }, label?.takeIf { it.isNotBlank() })
            .joinToString(" • ")
            .ifBlank { resolvedUrl() ?: "Open Link" }
}

data class MovieDetailsResponse(
    val title: String? = null,
    val poster: String? = null,
    val watchLinks: List<LinkEntry> = emptyList(),
    val downloadLinks: List<LinkEntry> = emptyList(),
    val links: List<LinkEntry> = emptyList()
)

data class StreamSession(
    val title: String,
    val url: String
)

interface LumiApiService {
    @GET("movies")
    suspend fun getLatestMovies(@Query("page") page: Int = 1): MoviesResponse

    @GET("search")
    suspend fun searchMovies(
        @Query("q") query: String,
        @Query("page") page: Int = 1
    ): MoviesResponse

    @GET("details")
    suspend fun getMovieDetails(@Query("url") url: String): MovieDetailsResponse
}

object LumiNetwork {
    val api: LumiApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://hdhub-api.vercel.app/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LumiApiService::class.java)
    }
}

data class LumiUiState(
    val showWelcome: Boolean = true,
    val movies: List<MovieItem> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val searchQuery: String = "",
    val activeQuery: String = "",
    val selectedMovie: MovieItem? = null,
    val details: MovieDetailsResponse? = null,
    val isDetailsLoading: Boolean = false,
    val detailsError: String? = null,
    val activeStream: StreamSession? = null
)

class LumiViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(LumiUiState(isLoading = true))
    val uiState: StateFlow<LumiUiState> = _uiState.asStateFlow()

    init {
        loadLatestMovies()
        viewModelScope.launch {
            delay(2200)
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

    fun loadLatestMovies() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                activeQuery = ""
            )
            try {
                val response = LumiNetwork.api.getLatestMovies(1)
                _uiState.value = _uiState.value.copy(
                    movies = response.movies,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Failed to load data. Check your internet or server."
                )
            }
        }
    }

    fun search(queryText: String = _uiState.value.searchQuery) {
        val trimmed = queryText.trim()
        if (trimmed.isEmpty()) {
            loadLatestMovies()
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                activeQuery = trimmed
            )
            try {
                val response = LumiNetwork.api.searchMovies(trimmed, 1)
                _uiState.value = _uiState.value.copy(
                    movies = response.movies,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Search failed. Please try again."
                )
            }
        }
    }

    fun openMovieDetails(movie: MovieItem) {
        _uiState.value = _uiState.value.copy(
            selectedMovie = movie,
            details = null,
            isDetailsLoading = true,
            detailsError = null
        )
        val targetUrl = movie.link
        if (targetUrl.isNullOrBlank()) {
            _uiState.value = _uiState.value.copy(
                isDetailsLoading = false,
                detailsError = "No link found for this item."
            )
            return
        }
        viewModelScope.launch {
            try {
                val response = LumiNetwork.api.getMovieDetails(targetUrl)
                _uiState.value = _uiState.value.copy(
                    details = response,
                    isDetailsLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isDetailsLoading = false,
                    detailsError = "Could not load details. You can still open the page link."
                )
            }
        }
    }

    fun closeMovieDetails() {
        _uiState.value = _uiState.value.copy(
            selectedMovie = null,
            details = null,
            isDetailsLoading = false,
            detailsError = null
        )
    }

    fun startStream(title: String, url: String) {
        _uiState.value = _uiState.value.copy(
            activeStream = StreamSession(title = title, url = url)
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
            fadeIn(animationSpec = tween(420)) togetherWith fadeOut(animationSpec = tween(320))
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
        LumiStreamPlayerDialog(
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
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Liquid Glass Cinema",
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
                        .padding(horizontal = 20.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = "Enter Cinema",
                        color = Color(0xFF67E8F9),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Text(
                    text = "Samir Ahmed",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
            }
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
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "LumiPlay",
                                color = Color.White,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (state.activeQuery.isEmpty()) "Latest Releases" else "Search: ${state.activeQuery}",
                                color = Color(0xFF93C5FD),
                                fontSize = 13.sp
                            )
                        }

                        if (state.activeQuery.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    lumiViewModel.onQueryChange("")
                                    lumiViewModel.loadLatestMovies()
                                }
                            ) {
                                Text("Latest", color = Color(0xFF67E8F9))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { lumiViewModel.onQueryChange(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .border(
                                width = 1.dp,
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.30f),
                                        Color.Cyan.copy(alpha = 0.20f)
                                    )
                                ),
                                shape = RoundedCornerShape(20.dp)
                            ),
                        placeholder = {
                            Text(
                                text = "Search movies or series...",
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 14.sp
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
                                    lumiViewModel.loadLatestMovies()
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
                        shape = RoundedCornerShape(20.dp)
                    )
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
                                text = state.errorMessage ?: "",
                                color = Color(0xFFFCA5A5),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(onClick = { lumiViewModel.loadLatestMovies() }) {
                                Text("Try Again")
                            }
                        }
                    }

                    state.movies.isEmpty() -> {
                        Text(
                            text = "No results found.",
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
                            items(state.movies) { movie ->
                                LiquidMovieCard(
                                    movie = movie,
                                    onClick = { lumiViewModel.openMovieDetails(movie) }
                                )
                            }
                        }
                    }
                }
            }
        }

        val selectedMovie = state.selectedMovie
        if (selectedMovie != null) {
            MovieDetailsDialog(
                movie = selectedMovie,
                details = state.details,
                isLoading = state.isDetailsLoading,
                errorText = state.detailsError,
                onDismiss = { lumiViewModel.closeMovieDetails() },
                onStreamLink = { title, url -> lumiViewModel.startStream(title, url) }
            )
        }
    }
}

@Composable
fun LiquidMovieCard(
    movie: MovieItem,
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
                        Color.White.copy(alpha = 0.30f),
                        Color.Cyan.copy(alpha = 0.10f)
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
            if (!movie.poster.isNullOrBlank()) {
                AsyncImage(
                    model = movie.poster,
                    contentDescription = movie.title ?: "Poster",
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
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = movie.title?.trim().orEmpty().ifEmpty { "Untitled" },
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

private data class BadgeStyle(
    val label: String,
    val bgColor: Color,
    val fgColor: Color,
    val icon: ImageVector
)

private fun resolveLinkBadge(categoryTag: String, titleText: String): BadgeStyle {
    val combined = "$categoryTag$titleText".lowercase()
    return when {
        "1080p" in combined -> BadgeStyle("1080P", Color(0x3322D3EE), Color(0xFF67E8F9), Icons.Rounded.Hd)
        "720p" in combined -> BadgeStyle("720P", Color(0x3360A5FA), Color(0xFF93C5FD), Icons.Rounded.Hd)
        "480p" in combined -> BadgeStyle("480P", Color(0x33A78BFA), Color(0xFFC4B5FD), Icons.Rounded.Movie)
        categoryTag.equals("Watch", ignoreCase = true) || "watch" in combined ->
            BadgeStyle("STREAM", Color(0x3334D399), Color(0xFF6EE7B7), Icons.Rounded.PlayArrow)
        categoryTag.equals("Download", ignoreCase = true) || "download" in combined ->
            BadgeStyle("DL", Color(0x33FBBF24), Color(0xFFFDE68A), Icons.Rounded.Download)
        else -> BadgeStyle(categoryTag.uppercase(), Color(0x33CBD5E1), Color(0xFFE2E8F0), Icons.Rounded.Link)
    }
}

@Composable
fun MovieDetailsDialog(
    movie: MovieItem,
    details: MovieDetailsResponse?,
    isLoading: Boolean,
    errorText: String?,
    onDismiss: () -> Unit,
    onStreamLink: (String, String) -> Unit
) {
    val context = LocalContext.current
    val previewPoster = details?.poster?.takeIf { it.isNotBlank() } ?: movie.poster

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
    }

    fun openExternalBrowser(rawUrl: String?) {
        if (rawUrl.isNullOrBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl))
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
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
                if (!previewPoster.isNullOrBlank()) {
                    AsyncImage(
                        model = previewPoster,
                        contentDescription = movie.title ?: "Poster preview",
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
                        text = details?.title?.takeIf { it.isNotBlank() } ?: (movie.title ?: "Details"),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Liquid Stream & Links",
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

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFF67E8F9))
                }
            } else {
                val allLinks = remember(details) {
                    val combined = mutableListOf<Pair<String, LinkEntry>>()
                    details?.watchLinks?.forEach { combined.add("Watch" to it) }
                    details?.downloadLinks?.forEach { combined.add("Download" to it) }
                    details?.links?.forEach { combined.add("Link" to it) }
                    combined.filter { !it.second.resolvedUrl().isNullOrBlank() }
                }

                if (errorText != null) {
                    Text(
                        text = errorText,
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    if (!movie.link.isNullOrBlank()) {
                        item {
                            LinkActionCard(
                                categoryTag = "Page",
                                title = "Movie Source Page",
                                url = movie.link,
                                onStream = { onStreamLink(movie.title ?: "Stream", movie.link) },
                                onCopy = { copyToClipboard("Page Link", movie.link) },
                                onBrowser = { openExternalBrowser(movie.link) }
                            )
                        }
                    }

                    items(allLinks) { (tag, entry) ->
                        val url = entry.resolvedUrl() ?: return@items
                        LinkActionCard(
                            categoryTag = tag,
                            title = entry.resolvedTitle(),
                            url = url,
                            onStream = { onStreamLink(entry.resolvedTitle(), url) },
                            onCopy = { copyToClipboard(entry.resolvedTitle(), url) },
                            onBrowser = { openExternalBrowser(url) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LinkActionCard(
    categoryTag: String,
    title: String,
    url: String,
    onStream: () -> Unit,
    onCopy: () -> Unit,
    onBrowser: () -> Unit
) {
    val badge = resolveLinkBadge(categoryTag, title)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badge.bgColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = badge.icon,
                        contentDescription = null,
                        tint = badge.fgColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = badge.label,
                        color = badge.fgColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x3322D3EE))
                    .border(1.dp, Color(0x6622D3EE), RoundedCornerShape(12.dp))
                    .clickable { onStream() }
                    .padding(vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color(0xFF67E8F9),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Stream in App",
                    color = Color(0xFF67E8F9),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy Link",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(17.dp)
                )
            }

            IconButton(
                onClick = onBrowser,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Icon(
                    imageVector = Icons.Rounded.OpenInBrowser,
                    contentDescription = "Open in Browser",
                    tint = Color(0xFF93C5FD),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}


@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LumiStreamPlayerDialog(
    session: StreamSession,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    fun looksLikeVideoUrl(raw: String?): Boolean {
        val u = raw?.lowercase().orEmpty()
        return u.endsWith(".mp4") ||
            u.endsWith(".m3u8") ||
            u.endsWith(".mkv") ||
            u.endsWith(".webm") ||
            u.contains(".mp4?") ||
            u.contains(".m3u8?") ||
            u.contains("/video") ||
            u.contains("stream")
    }

    var currentStreamUrl by remember(session.url) { mutableStateOf(session.url) }
    var useExoPlayer by remember(session.url) { mutableStateOf(looksLikeVideoUrl(session.url)) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val blockedKeywords = remember {
        listOf(
            "casino", "bet", "poker", "slot", "adsterra", "propeller",
            "exoclick", "juicyads", "popads", "hilltopads", "clickadu",
            "doubleclick", "googlesyndication", "redirect"
        )
    }

    val exoPlayer = remember(currentStreamUrl, useExoPlayer) {
        if (useExoPlayer) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(currentStreamUrl))
                prepare()
                playWhenReady = true
            }
        } else {
            null
        }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0E1426))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = session.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (useExoPlayer) "Direct Video Player" else "Smart Stream View",
                        color = Color(0xFF67E8F9),
                        fontSize = 11.sp
                    )
                }

                if (useExoPlayer) {
                    TextButton(
                        onClick = { useExoPlayer = false }
                    ) {
                        Text("Page", color = Color(0xFF93C5FD), fontSize = 12.sp)
                    }
                } else {
                    TextButton(
                        onClick = {
                            if (webViewRef?.canGoBack() == true) {
                                webViewRef?.goBack()
                            }
                        }
                    ) {
                        Text("Back", color = Color(0xFF67E8F9), fontSize = 12.sp)
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close Stream",
                        tint = Color.White
                    )
                }
            }

            if (useExoPlayer && exoPlayer != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
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
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.setSupportMultipleWindows(false)
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val rawUrl = request?.url?.toString().orEmpty()
                                    val lower = rawUrl.lowercase()
                                    if (blockedKeywords.any { key -> lower.contains(key) }) {
                                        Toast.makeText(ctx, "Ad redirect blocked", Toast.LENGTH_SHORT).show()
                                        return true
                                    }
                                    if (looksLikeVideoUrl(rawUrl)) {
                                        currentStreamUrl = rawUrl
                                        useExoPlayer = true
                                        return true
                                    }
                                    return false
                                }

                                override fun onLoadResource(view: WebView?, url: String?) {
                                    super.onLoadResource(view, url)
                                    if (!useExoPlayer && looksLikeVideoUrl(url) && url != null) {
                                        currentStreamUrl = url
                                        useExoPlayer = true
                                    }
                                }
                            }
                            loadUrl(session.url)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
