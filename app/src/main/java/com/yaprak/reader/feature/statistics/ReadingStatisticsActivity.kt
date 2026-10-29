package com.yaprak.reader.feature.statistics

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yaprak.reader.R
import com.yaprak.reader.YaprakApplication
import com.yaprak.reader.core.StartupState
import com.yaprak.reader.ui.theme.YaprakTheme

class ReadingStatisticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as YaprakApplication).container
        val factory = viewModelFactory { initializer { ReadingStatisticsViewModel(container.readingDuration) } }
        val completionFactory = viewModelFactory { initializer { BookCompletionViewModel(container.bookCompletions) } }
        val weeklyFactory = viewModelFactory { initializer { WeeklyReadingGoalViewModel(container.weeklyReadingGoal) } }
        val goalFactory = viewModelFactory { initializer { DailyReadingGoalViewModel(container.readingGoal) } }
        setContent {
            YaprakTheme {
                val startup by container.startup.state.collectAsStateWithLifecycle()
                when (startup) {
                    StartupState.Ready -> ReadingStatisticsRoute(viewModel(factory = factory),
                        viewModel<DailyReadingGoalViewModel>(factory = goalFactory), ::finish,
                        completionModel = viewModel(factory = completionFactory), weeklyModel = viewModel(factory = weeklyFactory))
                    StartupState.Loading, StartupState.Failed -> Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                            verticalArrangement = Arrangement.Center) {
                            Text(stringResource(if (startup == StartupState.Loading)
                                R.string.reading_statistics_startup_loading else R.string.reading_statistics_startup_failed),
                                Modifier.fillMaxWidth())
                            if (startup == StartupState.Failed) Button(container.startup::retry, Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.reading_statistics_retry), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                            Button(::finish, Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.reading_statistics_back), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
    companion object { fun intent(context: Context) = Intent(context, ReadingStatisticsActivity::class.java) }
}
