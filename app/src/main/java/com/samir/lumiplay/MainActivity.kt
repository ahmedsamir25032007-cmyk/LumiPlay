package com.samir.lumiplay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val detailsError: String? = null
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
                    detailsError = "Could not load details. You can open the page link."
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
                    radius = 750f
                )
            )
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0x5506B6D4), Color.Transparent),
                    center = Offset(920f, 1550f),
                    radius = 850f
                )
            ),
        content = content
    )
}

@Composable
fun LumiPlayRoot(lumiViewModel: LumiViewModel = viewModel()) {
    val state by lumiViewModel.uiState.collectAsState()

    if (state.showWelcome) {
        LumiPlayWelcomeScreen(
            onContinue = { lumiViewModel.enterAppNow() }
        )
    } else {
        LumiPlayHomeScreen(
            state = state,
            lumiViewModel = lumiViewModel
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
                                Color.White.copy(alpha = 0.14f),
                                Color.White.copy(alpha = 0.05f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.35f),
                                Color.White.copy(alpha = 0.08f)
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
                    color = Color.White.copy(alpha = 0.90f),
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
                onDismiss = { lumiViewModel.closeMovieDetails() }
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
                        Color.White.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.04f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.28f),
                        Color.White.copy(alpha = 0.06f)
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

@Composable
fun MovieDetailsDialog(
    movie: MovieItem,
    details: MovieDetailsResponse?,
    isLoading: Boolean,
    errorText: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    fun openExternalUrl(rawUrl: String?) {
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
                .heightIn(max = 580.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0E1324))
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.30f),
                            Color.Cyan.copy(alpha = 0.18f)
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = details?.title?.takeIf { it.isNotBlank() } ?: (movie.title ?: "Details"),
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    if (!movie.link.isNullOrBlank()) {
                        item {
                            LinkActionRow(
                                tag = "Page",
                                title = "Open Movie Page",
                                onClick = { openExternalUrl(movie.link) }
                            )
                        }
                    }

                    items(allLinks) { (tag, entry) ->
                        LinkActionRow(
                            tag = tag,
                            title = entry.resolvedTitle(),
                            onClick = { openExternalUrl(entry.resolvedUrl()) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LinkActionRow(
    tag: String,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = tag,
                color = Color(0xFF67E8F9),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Rounded.OpenInBrowser,
            contentDescription = "Open",
            tint = Color(0xFF93C5FD)
        )
    }
}
