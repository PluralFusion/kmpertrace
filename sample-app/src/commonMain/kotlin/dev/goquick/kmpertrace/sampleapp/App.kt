package dev.goquick.kmpertrace.sampleapp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import dev.goquick.kmpertrace.sampleapp.data.FakeDatabase
import dev.goquick.kmpertrace.sampleapp.data.FakeDownloader
import dev.goquick.kmpertrace.sampleapp.data.FakeNetworkService
import dev.goquick.kmpertrace.sampleapp.data.ProfileRepository
import dev.goquick.kmpertrace.sampleapp.model.ActivityEvent
import dev.goquick.kmpertrace.sampleapp.model.Contact
import dev.goquick.kmpertrace.sampleapp.model.LoadState
import dev.goquick.kmpertrace.sampleapp.model.Profile
import dev.goquick.kmpertrace.sampleapp.ui.ProfileViewModel
import dev.goquick.kmpertrace.sampleapp.ui.ProfileViewModel.Companion.DEFAULT_USER_ID

@Composable
fun App() {
    val viewModel = rememberProfileViewModel()

    LaunchedEffect(Unit) {
        viewModel.refreshAll(DEFAULT_USER_ID)
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ProfileScreen(
                state = viewModel.state,
                onRefreshAll = { viewModel.refreshAll(DEFAULT_USER_ID) },
                onRefreshActivity = { viewModel.refreshActivityOnly(DEFAULT_USER_ID) },
                onTriggerFailure = { viewModel.triggerFailure(DEFAULT_USER_ID) },
                onDownloadA = { viewModel.startDownload("DownloadA") },
                onDownloadB = { viewModel.startDownload("DownloadB") }
            )
        }
    }
}

@Composable
private fun rememberProfileViewModel(): ProfileViewModel {
    val scope = rememberCoroutineScope()
    return remember {
        ProfileViewModel(
            repo = ProfileRepository(FakeNetworkService(), FakeDatabase()),
            downloader = FakeDownloader(),
            scope = scope
        )
    }
}

@Composable
private fun ProfileScreen(
    state: dev.goquick.kmpertrace.sampleapp.model.ProfileScreenState,
    onRefreshAll: () -> Unit,
    onRefreshActivity: () -> Unit,
    onTriggerFailure: () -> Unit,
    onDownloadA: () -> Unit,
    onDownloadB: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        HeaderSection(
            onRefreshAll = onRefreshAll,
            onRefreshActivity = onRefreshActivity,
            onTriggerFailure = onTriggerFailure,
            onDownloadA = onDownloadA,
            onDownloadB = onDownloadB
        )
        ProfileCard(state.profile)
        ContactsCard(state.contacts)
        ActivityCard(state.activity)
        DownloadsCard(state.downloads)
    }
}

@Composable
private fun HeaderSection(
    onRefreshAll: () -> Unit,
    onRefreshActivity: () -> Unit,
    onTriggerFailure: () -> Unit,
    onDownloadA: () -> Unit,
    onDownloadB: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("KmperTrace Sample", style = MaterialTheme.typography.titleLarge)
            Text(
                "Profile + Contacts demo with traced, logged fake network/database calls.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRefreshAll) {
                    Text("Refresh all")
                }
                Button(onClick = onRefreshActivity) {
                    Text("Refresh activity")
                }
                Button(onClick = onTriggerFailure) {
                    Text("Trigger failure")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDownloadA) {
                    Text("Download A")
                }
                Button(onClick = onDownloadB) {
                    Text("Download B")
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(state: LoadState<Profile>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Profile", style = MaterialTheme.typography.titleMedium)
            when (state) {
                is LoadState.Loading -> Text("Loading profile…")
                is LoadState.Error -> Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                is LoadState.Success -> {
                    val profile = state.value
                    Text(profile.name, style = MaterialTheme.typography.titleLarge)
                    Text(profile.title, style = MaterialTheme.typography.bodyMedium)
                    Text("Email: ${profile.email}")
                    Text("Phone: ${profile.phone}")
                    Text("City: ${profile.city}")
                }
            }
        }
    }
}

@Composable
private fun ContactsCard(state: LoadState<List<Contact>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Contacts", style = MaterialTheme.typography.titleMedium)
            when (state) {
                is LoadState.Loading -> Text("Loading contacts…")
                is LoadState.Error -> Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                is LoadState.Success -> {
                    state.value.forEach { contact ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(contact.name, style = MaterialTheme.typography.titleSmall)
                            Text(contact.relationship, style = MaterialTheme.typography.bodySmall)
                            Text(contact.email, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(state: LoadState<List<ActivityEvent>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Activity", style = MaterialTheme.typography.titleMedium)
            when (state) {
                is LoadState.Loading -> Text("Loading activity…")
                is LoadState.Error -> Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
                is LoadState.Success -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.value) { event ->
                            Column {
                                Text(event.label, style = MaterialTheme.typography.titleSmall)
                                Text(event.timestamp, style = MaterialTheme.typography.bodySmall)
                                Text(event.description, style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsCard(downloads: List<dev.goquick.kmpertrace.sampleapp.model.DownloadState>) {
    if (downloads.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Downloads", style = MaterialTheme.typography.titleMedium)
            downloads.forEach { dl ->
                Column {
                    Text("${dl.label} (${dl.status})", style = MaterialTheme.typography.titleSmall)
                    Text("Progress: ${dl.progressPercent}%", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
