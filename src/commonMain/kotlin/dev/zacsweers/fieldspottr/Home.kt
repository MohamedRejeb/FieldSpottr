// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.fieldspottr

import androidx.compose.foundation.layout.Arrangement.spacedBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.slack.circuit.overlay.LocalOverlayHost
import com.slack.circuit.retained.collectAsRetainedState
import com.slack.circuit.retained.rememberRetained
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuitx.overlays.alertDialogOverlay
import dev.zacsweers.fieldspottr.data.Area
import dev.zacsweers.fieldspottr.data.NYC_TZ
import dev.zacsweers.fieldspottr.data.PermitRepository
import dev.zacsweers.fieldspottr.parcel.CommonParcelize
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock.System
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@CommonParcelize
data object HomeScreen : Screen {
  data class State(
    val date: LocalDate,
    val selectedGroup: String,
    val loadingMessage: String?,
    val permits: PermitState?,
    val eventSink: (Event) -> Unit = {},
  ) : CircuitUiState

  sealed interface Event {
    data object Refresh : Event

    data class FilterDate(val date: LocalDate) : Event

    data class ChangeGroup(val group: String) : Event
  }
}

@Composable
fun HomePresenter(repository: PermitRepository): HomeScreen.State {
  var selectedDate by rememberRetained {
    mutableStateOf(System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
  }
  var populateDb by rememberRetained { mutableStateOf(false) }
  var forceRefresh by rememberRetained { mutableStateOf(false) }
  var loadingMessage by rememberRetained { mutableStateOf<String?>(null) }
  var selectedGroup by rememberRetained { mutableStateOf(Area.entries[0].fieldGroups[0].name) }

  val permitsFlow =
    rememberRetained(selectedDate, selectedGroup) {
      repository
        .permitsFlow(selectedDate, selectedGroup)
        .map(PermitState::fromPermits)
        .flowOn(Dispatchers.IO)
    }
  val permits by permitsFlow.collectAsRetainedState(null)

  if (populateDb) {
    LaunchedEffect(Unit) {
      loadingMessage = "Populating DB..."
      val successful = repository.populateDb(forceRefresh)
      loadingMessage =
        if (successful) {
          null
        } else {
          "Failed to fetch areas. Please check connection and try again."
        }
      forceRefresh = false
      populateDb = false
    }
  }
  return HomeScreen.State(
    date = selectedDate,
    selectedGroup = selectedGroup,
    loadingMessage = loadingMessage,
    permits = permits,
  ) { event ->
    when (event) {
      is HomeScreen.Event.Refresh -> {
        forceRefresh = true
        populateDb = true
      }
      is HomeScreen.Event.FilterDate -> {
        selectedDate = event.date
      }
      is HomeScreen.Event.ChangeGroup -> {
        selectedGroup = event.group
      }
    }
  }
}

@Composable
fun Home(state: HomeScreen.State, modifier: Modifier = Modifier) {
  val snackbarHostState = remember { SnackbarHostState() }
  LaunchedEffect(state.loadingMessage) {
    state.loadingMessage?.let {
      snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Indefinite)
    }
  }
  Scaffold(
    modifier = modifier,
    topBar = {
      CenterAlignedTopAppBar(
        title = {
          Text("Field Spottr", fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic)
        },
        actions = {
          IconButton(onClick = { state.eventSink(HomeScreen.Event.Refresh) }) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
          }
        },
      )
    },
    floatingActionButton = {
      DateSelector(state.date) { newDate -> state.eventSink(HomeScreen.Event.FilterDate(newDate)) }
    },
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState) { snackbarData ->
        Snackbar(snackbarData = snackbarData)
      }
    },
  ) { innerPadding ->
    Column(Modifier.padding(innerPadding), verticalArrangement = spacedBy(16.dp)) {
      GroupSelector(
        state.selectedGroup,
        modifier = Modifier.align(CenterHorizontally).padding(horizontal = 16.dp),
      ) { newGroup ->
        state.eventSink(HomeScreen.Event.ChangeGroup(newGroup))
      }

      val overlayHost = LocalOverlayHost.current
      val scope = rememberCoroutineScope()
      PermitGrid(
        state.selectedGroup,
        state.permits,
        modifier = Modifier.align(CenterHorizontally),
      ) { event ->
        scope.launch {
          overlayHost.show(
            alertDialogOverlay(
              title = { Text(event.title) },
              text = { Text(event.description) },
              confirmButton = { onClick -> TextButton(onClick) { Text("Done") } },
              dismissButton = null,
            )
          )
        }
      }
    }
  }
}

@Stable
data class PermitState(val fields: Map<String, List<FieldState>>) {
  @Immutable
  sealed interface FieldState {
    data object Free : FieldState

    data class Reserved(
      val start: Int,
      val end: Int,
      val timeRange: String,
      val title: String,
      val description: String,
    ) : FieldState {
      val duration = end - start
    }

    companion object {
      val EMPTY = List(24) { Free }

      fun fromPermits(permits: List<DbPermit>): List<FieldState> {
        if (permits.isEmpty()) {
          return EMPTY
        }

        val sortedPermits = permits.sortedBy { it.start }

        val elements = mutableListOf<FieldState>()
        var currentPermitIndex = 0
        var hour = 0
        while (hour < 24) {
          val permit = sortedPermits[currentPermitIndex]
          val startDateTime = Instant.fromEpochMilliseconds(permit.start).toLocalDateTime(NYC_TZ)
          val startHour = startDateTime.hour
          if (startHour == hour) {
            val durationHours = (permit.end - permit.start).milliseconds.inWholeHours.toInt()
            val endTime = startHour + durationHours
            val startTimeString = EventTimeFormatter.format(startDateTime)
            val endTimeString =
              EventTimeFormatter.format(
                Instant.fromEpochMilliseconds(permit.end).toLocalDateTime(NYC_TZ)
              )
            val timeRange = "$startTimeString - $endTimeString"
            elements +=
              Reserved(
                start = startHour,
                end = endTime,
                timeRange = timeRange,
                title = permit.name,
                description =
                  """
                    $timeRange
                    Org: ${permit.org}
                    Status: ${permit.status}
                  """
                    .trimIndent(),
              )
            hour += durationHours
            if (currentPermitIndex == sortedPermits.lastIndex) {
              // Exhaust and break
              repeat(24 - endTime) { elements += Free }
              break
            } else {
              currentPermitIndex++
            }
          } else {
            // Pad free slots until next permit start
            repeat(startHour - hour) {
              elements += Free
              hour++
            }
          }
        }
        return elements
      }
    }
  }

  companion object {
    val EMPTY = fromPermits(emptyList())

    fun fromPermits(permits: List<DbPermit>): PermitState {
      val areasByName = Area.entries.associateBy { it.areaName }
      val fields =
        permits
          .groupBy { areasByName.getValue(it.area).fieldMappings.getValue(it.fieldId) }
          .mapKeys { it.key.name }
          .mapValues { (_, permits) -> FieldState.fromPermits(permits) }
      return PermitState(fields)
    }
  }
}
