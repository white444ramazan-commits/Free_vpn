package com.freevpn.client.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freevpn.client.data.model.VpnServerDisplay
import com.freevpn.client.ui.theme.BorderHairline
import com.freevpn.client.ui.theme.LabelCaps
import com.freevpn.client.ui.theme.Primary
import com.freevpn.client.ui.theme.StatusSuccess
import com.freevpn.client.ui.theme.TechnicalData
import com.freevpn.client.ui.theme.TextMuted
import com.freevpn.client.ui.util.countryCodeToFlag
import com.freevpn.client.ui.util.formatScore
import com.freevpn.client.ui.util.formatSpeed


// ── Servers Tab Content ─────────────────────────────────────────────────────────

private data class CountrySummary(
    val name: String,
    val code: String,
    val serverCount: Int
)

// ── Countries → regional server directory ───────────────────────────────────────

@Composable
fun ServersTabContent(
    viewModel: VpnViewModel,
    onServerSelected: (VpnServerDisplay) -> Unit
) {
    val listState = rememberLazyListState()
    val selectedCountry = viewModel.selectedCountryFilter
    val query = viewModel.searchQuery.trim()

    val countrySummaries = remember(viewModel.displayServers, query) {
        viewModel.displayServers
            .groupBy { it.countryLong }
            .mapNotNull { (name, servers) ->
                val first = servers.firstOrNull() ?: return@mapNotNull null
                CountrySummary(
                    name = name,
                    code = first.countryShort,
                    serverCount = servers.size
                )
            }
            .filter { country ->
                query.isBlank() ||
                    country.name.contains(query, ignoreCase = true) ||
                    country.code.contains(query, ignoreCase = true)
            }
            .sortedBy { it.name.lowercase() }
    }

    // Scroll to top whenever the user changes country, search, sorting or the feed.
    LaunchedEffect(
        viewModel.activeSort,
        selectedCountry,
        viewModel.residentialOnly,
        query,
        viewModel.displayServers
    ) {
        listState.scrollToItem(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        if (selectedCountry == null) {
            Text(
                text = "Countries",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = Primary
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(StatusSuccess)
                )
                Text(
                    text = "${viewModel.availableCountries.size} countries · ${viewModel.displayServers.size} regional servers",
                    style = TechnicalData,
                    color = TextMuted
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xFF171717))
                        .clickable {
                            viewModel.selectedCountryFilter = null
                            viewModel.searchQuery = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Back to countries",
                        tint = Primary,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedCountry,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.4).sp
                        ),
                        color = Primary,
                        maxLines = 1
                    )
                    Text(
                        text = "${viewModel.countryServerCounts[selectedCountry] ?: 0} regional servers available",
                        style = TechnicalData,
                        color = TextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search is scoped to country names on the first page and to nodes inside
        // the selected country on the second page.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, BorderHairline, RoundedCornerShape(10.dp))
                .background(Color(0xFF141414))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                BasicTextField(
                    value = viewModel.searchQuery,
                    onValueChange = { viewModel.searchQuery = it },
                    textStyle = TechnicalData.copy(color = Primary, fontSize = 14.sp),
                    cursorBrush = SolidColor(Primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        if (viewModel.searchQuery.isEmpty()) {
                            Text(
                                text = if (selectedCountry == null)
                                    "Search countries..."
                                else
                                    "Search regions, IP, or hostname...",
                                style = TechnicalData.copy(color = TextMuted, fontSize = 14.sp)
                            )
                        }
                        innerTextField()
                    }
                )
                if (viewModel.searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear Search",
                        tint = TextMuted,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { viewModel.searchQuery = "" }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedCountry == null) {
            // Page 1: countries only. Tapping a country opens its own server list.
            if (viewModel.displayServers.isEmpty()) {
                DirectoryEmptyState(viewModel = viewModel, message = "No countries available")
            } else if (countrySummaries.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No countries match your search",
                        style = TechnicalData,
                        color = TextMuted
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = countrySummaries,
                        key = { it.code.ifBlank { it.name } },
                        contentType = { "country" }
                    ) { country ->
                        CountryDirectoryCard(
                            country = country,
                            onClick = {
                                viewModel.searchQuery = ""
                                viewModel.selectedCountryFilter = country.name
                            }
                        )
                    }
                }
            }
        } else {
            // Page 2: server/region nodes belonging only to the selected country.
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        label = "BEST OVERALL",
                        isSelected = viewModel.activeSort == SortCriteria.OVERALL,
                        onClick = { viewModel.setSortCriteria(SortCriteria.OVERALL) }
                    )
                }
                item {
                    FilterChip(
                        label = "TOP SCORE",
                        isSelected = viewModel.activeSort == SortCriteria.SCORE,
                        onClick = { viewModel.setSortCriteria(SortCriteria.SCORE) }
                    )
                }
                item {
                    FilterChip(
                        label = "LOWEST PING",
                        isSelected = viewModel.activeSort == SortCriteria.PING,
                        onClick = { viewModel.setSortCriteria(SortCriteria.PING) }
                    )
                }
                item {
                    FilterChip(
                        label = "FASTEST",
                        isSelected = viewModel.activeSort == SortCriteria.SPEED,
                        onClick = { viewModel.setSortCriteria(SortCriteria.SPEED) }
                    )
                }
                item {
                    FilterChip(
                        label = "MOST ACTIVE",
                        isSelected = viewModel.activeSort == SortCriteria.SESSIONS,
                        onClick = { viewModel.setSortCriteria(SortCriteria.SESSIONS) }
                    )
                }
                item {
                    val countryResidentialCount =
                        viewModel.filteredServers.count { it.isResidential }
                    FilterChip(
                        label = "RESIDENTIAL ($countryResidentialCount)",
                        isSelected = viewModel.residentialOnly,
                        onClick = { viewModel.residentialOnly = !viewModel.residentialOnly }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (viewModel.filteredServers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (viewModel.lastLoadError != null && viewModel.displayServers.isEmpty()) {
                        DirectoryEmptyState(viewModel = viewModel, message = "Could not load servers")
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No regional servers found",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Try another search or clear the residential filter",
                                style = TechnicalData,
                                color = TextMuted.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = viewModel.filteredServers,
                        key = { it.ip },
                        contentType = { "server_node" }
                    ) { srv ->
                        val isSelected = srv.ip == viewModel.selectedServer?.ip
                        AdvancedServerCard(
                            server = srv,
                            isSelected = isSelected,
                            onClick = { onServerSelected(srv) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun CountryDirectoryCard(
    country: CountrySummary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .border(1.dp, BorderHairline, RoundedCornerShape(11.dp))
            .background(Color(0xFF101010))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(Color(0xFF1D1D1D))
                .border(1.dp, BorderHairline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = countryCodeToFlag(country.code),
                fontSize = 21.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = country.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${country.serverCount} servers / regions",
                style = TechnicalData,
                color = TextMuted
            )
        }
        Text(
            text = country.serverCount.toString(),
            style = TechnicalData.copy(fontWeight = FontWeight.Bold),
            color = StatusSuccess,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(StatusSuccess.copy(alpha = 0.10f))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = "Open ${country.name}",
            tint = TextMuted,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun DirectoryEmptyState(
    viewModel: VpnViewModel,
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        if (viewModel.lastLoadError != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Fetch failed",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = viewModel.lastLoadError ?: message,
                    style = TechnicalData,
                    color = TextMuted.copy(alpha = 0.6f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                FilterChip(
                    label = "RETRY",
                    isSelected = false,
                    onClick = { viewModel.loadServers() }
                )
            }
        } else {
            Text(text = message, style = TechnicalData, color = TextMuted)
        }
    }
}

// ── Advanced Server Card (Optimized 120 FPS Rendering) ─────────────────────────

@Composable
private fun AdvancedServerCard(
    server: VpnServerDisplay,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    // Sub-15ms reports are physically implausible for an internet relay — treat
    // them as suspect data, not as a great latency.
    val pingColor = when {
        server.ping < 15 -> Color(0xFFFFB300)
        server.ping < 60 -> Color(0xFF00E676)
        server.ping < 120 -> Color(0xFF00E5FF)
        else -> Color(0xFFFFB300)
    }

    val qualityColor = when {
        server.qualityScore > 0.7f -> Color(0xFF00E676)
        server.qualityScore > 0.4f -> Color(0xFF00E5FF)
        else -> Color(0xFFFFB300)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(
                1.dp,
                if (isSelected) Primary else BorderHairline,
                RoundedCornerShape(10.dp)
            )
            .background(if (isSelected) Color(0xFF181818) else Color(0xFF101010))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Flag Badge
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1E1E1E), CircleShape)
                    .border(1.dp, BorderHairline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = server.flagEmoji,
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = server.hostName.takeIf { it.isNotBlank() && it != server.ip } ?: "VPN Gate node",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (server.isResidential) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "🏠 RESIDENTIAL",
                            style = LabelCaps.copy(fontSize = 7.sp, fontWeight = FontWeight.Bold),
                            color = Color(0xFF00E5FF),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF00E5FF).copy(alpha = 0.10f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .border(1.dp, StatusSuccess, RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = "ACTIVE",
                                style = LabelCaps.copy(fontSize = 8.sp),
                                color = StatusSuccess
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${server.countryLong} · ${server.ip}",
                    style = TechnicalData,
                    color = TextMuted
                )
            }

            // Ping badge
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = server.pingText,
                    style = TechnicalData.copy(fontWeight = FontWeight.SemiBold),
                    color = pingColor
                )
                Text(
                    text = server.formattedSpeed,
                    style = TechnicalData.copy(fontSize = 10.sp),
                    color = TextMuted
                )
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Selected",
                    tint = Primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quality bar + stats row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Quality progress bar (Lightweight zero-allocation Box)
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "QUALITY",
                        style = LabelCaps.copy(fontSize = 9.sp),
                        color = TextMuted
                    )
                    Text(
                        text = "${(server.qualityScore * 100).toInt()}%",
                        style = LabelCaps.copy(fontSize = 9.sp),
                        color = qualityColor
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF1E1E1E))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(server.qualityScore.coerceIn(0.01f, 1f))
                            .fillMaxHeight()
                            .background(qualityColor)
                    )
                }
            }

            // Score metric
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "SCORE",
                    style = LabelCaps.copy(fontSize = 9.sp),
                    color = TextMuted
                )
                Text(
                    text = server.formattedScore,
                    style = TechnicalData.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    color = Primary
                )
            }

            // Sessions count
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "SESSIONS",
                    style = LabelCaps.copy(fontSize = 9.sp),
                    color = TextMuted
                )
                Text(
                    text = "${server.numVpnSessions}",
                    style = TechnicalData.copy(fontSize = 11.sp),
                    color = TextMuted
                )
            }
        }
    }
}



// ── Filter Chip ─────────────────────────────────────────────────────────────────

@Composable
fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(
                1.dp,
                if (isSelected) Primary else BorderHairline,
                RoundedCornerShape(6.dp)
            )
            .background(if (isSelected) Color(0xFF222222) else Color(0xFF141414))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = LabelCaps.copy(fontSize = 10.sp),
            color = if (isSelected) Primary else TextMuted
        )
    }
}
