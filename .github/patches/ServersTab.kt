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


// ── Countries → Regions → Servers ───────────────────────────────────────────────

private data class CountrySummary(
    val name: String,
    val code: String,
    val serverCount: Int
)

private data class RegionSummary(
    val name: String,
    val citySummary: String,
    val serverCount: Int,
    val unavailable: Boolean = false
)

@Composable
fun ServersTabContent(
    viewModel: VpnViewModel,
    onServerSelected: (VpnServerDisplay) -> Unit
) {
    val listState = rememberLazyListState()
    val selectedCountry = viewModel.selectedCountryFilter
    val selectedRegion = viewModel.selectedRegionFilter
    val query = viewModel.searchQuery.trim()
    val isCountriesPage = selectedCountry == null
    val isRegionsPage = selectedCountry != null && selectedRegion == null

    LaunchedEffect(selectedCountry, selectedRegion) {
        listState.scrollToItem(0)
        if (selectedCountry != null) {
            viewModel.loadRegionsForCountry(selectedCountry)
        } else {
            viewModel.cancelRegionLookups()
        }
    }

    val countrySummaries = remember(viewModel.displayServers, query) {
        viewModel.displayServers
            .groupBy { it.countryLong }
            .mapNotNull { (name, servers) ->
                val first = servers.firstOrNull() ?: return@mapNotNull null
                CountrySummary(name, first.countryShort, servers.size)
            }
            .filter {
                query.isBlank() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.code.contains(query, ignoreCase = true)
            }
            .sortedBy { it.name.lowercase() }
    }

    val countryServers = remember(viewModel.displayServers, selectedCountry) {
        if (selectedCountry == null) emptyList()
        else viewModel.displayServers.filter { it.countryLong == selectedCountry }
    }

    val regionSummaries = remember(
        countryServers,
        viewModel.regionLocations,
        viewModel.regionLookupCountry,
        query
    ) {
        val groups = countryServers.groupBy { server ->
            viewModel.regionLocations[server.ip]?.region?.takeIf { it.isNotBlank() }
                ?: "Location unavailable"
        }
        groups.map { (regionName, servers) ->
            val cities = servers.mapNotNull { server ->
                viewModel.regionLocations[server.ip]?.city?.takeIf { it.isNotBlank() }
            }.distinct().take(3).joinToString(" · ")
            RegionSummary(
                name = regionName,
                citySummary = cities.ifBlank {
                    if (regionName == "Location unavailable") {
                        "IP location could not be determined"
                    } else {
                        "Regional server locations"
                    }
                },
                serverCount = servers.size,
                unavailable = regionName == "Location unavailable"
            )
        }
            .filter {
                query.isBlank() ||
                    it.name.contains(query, ignoreCase = true) ||
                    it.citySummary.contains(query, ignoreCase = true)
            }
            .sortedWith(compareBy<RegionSummary> { it.unavailable }.thenBy { it.name.lowercase() })
    }

    val knownRegionCount = regionSummaries.count { !it.unavailable }
    val isLookingUp = selectedCountry != null &&
        viewModel.regionLookupCountry == selectedCountry
    val unresolvedCount = if (isLookingUp) {
        (viewModel.regionLookupTotal - viewModel.regionLookupProgress).coerceAtLeast(0)
    } else {
        0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        if (isCountriesPage) {
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
                    text = "${countrySummaries.size} countries · ${viewModel.displayServers.size} servers",
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
                            if (selectedRegion != null) {
                                viewModel.selectedRegionFilter = null
                            } else {
                                viewModel.selectedCountryFilter = null
                                viewModel.selectedRegionFilter = null
                                viewModel.cancelRegionLookups()
                            }
                            viewModel.searchQuery = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = if (isRegionsPage) "Back to countries" else "Back to regions",
                        tint = Primary,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isRegionsPage) selectedCountry.orEmpty() else selectedRegion.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.4).sp
                        ),
                        color = Primary,
                        maxLines = 1
                    )
                    Text(
                        text = if (isRegionsPage) {
                            "${knownRegionCount} regions identified · ${countryServers.size} servers"
                        } else {
                            selectedCountry.orEmpty()
                        },
                        style = TechnicalData,
                        color = TextMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Search applies to the current level: country, region/city, or server.
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
                                text = when {
                                    isCountriesPage -> "Search countries..."
                                    isRegionsPage -> "Search regions or cities..."
                                    else -> "Search IP or hostname..."
                                },
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

        when {
            isCountriesPage -> {
                if (viewModel.displayServers.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        DirectoryEmptyState(viewModel, "No countries available")
                    }
                } else if (countrySummaries.isEmpty()) {
                    EmptyDirectoryMessage("No countries match your search")
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(countrySummaries, key = { it.name }, contentType = { "country" }) { country ->
                            CountryDirectoryCard(country) {
                                viewModel.searchQuery = ""
                                viewModel.selectedRegionFilter = null
                                viewModel.selectedCountryFilter = country.name
                            }
                        }
                    }
                }
            }

            isRegionsPage -> {
                Text(
                    text = "Locations are approximate estimates based on each relay IP address.",
                    style = TechnicalData,
                    color = TextMuted.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (isLookingUp) {
                    Text(
                        text = "Resolving relay locations: ${viewModel.regionLookupProgress}/${viewModel.regionLookupTotal}",
                        style = TechnicalData,
                        color = StatusSuccess,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                if (regionSummaries.isEmpty() && isLookingUp) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Finding regions...", color = Primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${viewModel.regionLookupProgress} of ${viewModel.regionLookupTotal} servers checked",
                                style = TechnicalData,
                                color = TextMuted
                            )
                        }
                    }
                } else if (regionSummaries.isEmpty()) {
                    EmptyDirectoryMessage("No regions match your search")
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(regionSummaries, key = { it.name }, contentType = { "region" }) { region ->
                            RegionDirectoryCard(region) {
                                viewModel.searchQuery = ""
                                viewModel.selectedRegionFilter = region.name
                            }
                        }
                    }
                    if (unresolvedCount > 0) {
                        Text(
                            text = "Still locating $unresolvedCount servers...",
                            style = TechnicalData,
                            color = TextMuted,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            }

            else -> {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip("BEST OVERALL", viewModel.activeSort == SortCriteria.OVERALL) {
                            viewModel.setSortCriteria(SortCriteria.OVERALL)
                        }
                    }
                    item {
                        FilterChip("TOP SCORE", viewModel.activeSort == SortCriteria.SCORE) {
                            viewModel.setSortCriteria(SortCriteria.SCORE)
                        }
                    }
                    item {
                        FilterChip("LOWEST PING", viewModel.activeSort == SortCriteria.PING) {
                            viewModel.setSortCriteria(SortCriteria.PING)
                        }
                    }
                    item {
                        FilterChip("FASTEST", viewModel.activeSort == SortCriteria.SPEED) {
                            viewModel.setSortCriteria(SortCriteria.SPEED)
                        }
                    }
                    item {
                        FilterChip("MOST ACTIVE", viewModel.activeSort == SortCriteria.SESSIONS) {
                            viewModel.setSortCriteria(SortCriteria.SESSIONS)
                        }
                    }
                    item {
                        val countryResidentialCount = viewModel.filteredServers.count { it.isResidential }
                        FilterChip("RESIDENTIAL ($countryResidentialCount)", viewModel.residentialOnly) {
                            viewModel.residentialOnly = !viewModel.residentialOnly
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (viewModel.filteredServers.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No servers in this region match your search",
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
                            items = viewModel.filteredServers,
                            key = { it.ip },
                            contentType = { "server_node" }
                        ) { server ->
                            AdvancedServerCard(
                                server = server,
                                isSelected = server.ip == viewModel.selectedServer?.ip,
                                onClick = { onServerSelected(server) }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun CountryDirectoryCard(country: CountrySummary, onClick: () -> Unit) {
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
            Text(countryCodeToFlag(country.code), fontSize = 21.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = country.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${country.serverCount} servers",
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
private fun RegionDirectoryCard(region: RegionSummary, onClick: () -> Unit) {
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
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFF1D1D1D))
                .border(1.dp, BorderHairline, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = if (region.unavailable) TextMuted else StatusSuccess,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = region.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (region.unavailable) TextMuted else Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = region.citySummary,
                style = TechnicalData,
                color = TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = region.serverCount.toString(),
            style = TechnicalData.copy(fontWeight = FontWeight.Bold),
            color = StatusSuccess,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(StatusSuccess.copy(alpha = 0.10f))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun EmptyDirectoryMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = message, style = TechnicalData, color = TextMuted)
    }
}

@Composable
private fun DirectoryEmptyState(viewModel: VpnViewModel, message: String) {
    if (viewModel.lastLoadError != null) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Fetch failed", style = MaterialTheme.typography.bodyLarge, color = Primary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = viewModel.lastLoadError ?: message,
                style = TechnicalData,
                color = TextMuted.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            FilterChip("RETRY", false) { viewModel.loadServers() }
        }
    } else {
        Text(text = message, style = TechnicalData, color = TextMuted)
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
        server.ping <= 0 -> TextMuted
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
                    if (server.provider != "VPN Gate") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = server.provider.uppercase(),
                            style = LabelCaps.copy(fontSize = 7.sp, fontWeight = FontWeight.Bold),
                            color = Color(0xFF80CBC4),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF80CBC4).copy(alpha = 0.10f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    if (server.availability == "recent") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "RECENTLY SEEN",
                            style = LabelCaps.copy(fontSize = 7.sp, fontWeight = FontWeight.Bold),
                            color = Color(0xFFFFB74D),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFFFFB74D).copy(alpha = 0.10f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
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
                    text = if (server.numVpnSessions < 0) "—" else "${server.numVpnSessions}",
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
