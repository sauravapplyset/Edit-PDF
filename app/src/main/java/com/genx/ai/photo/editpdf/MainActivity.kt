package com.genx.ai.photo.editpdf

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.genx.ai.photo.editpdf.presentation.screens.HomeScreen
import com.genx.ai.photo.editpdf.presentation.screens.PdfViewerScreen
import com.genx.ai.photo.editpdf.presentation.viewmodel.PdfViewerViewModel
import com.genx.ai.photo.editpdf.ui.theme.EditPDFTheme
import dagger.hilt.android.AndroidEntryPoint

// TODO: Add recent files list (store last 5 URIs in SharedPreferences or DataStore)
// TODO: Handle incoming PDF intents (View/Open from other apps via implicit intent)
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: PdfViewerViewModel

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        // TODO: Persist URI permission so the file can be re-opened after restart
        uri?.let {
            viewModel.openPdf(it.toString())
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let {
            viewModel.exportPdf(it.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EditPDFTheme {
                val navController = rememberNavController()
                
                // Retrieve or initialize viewmodel scoped to the nav graph or composition
                viewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("home") {
                            HomeScreen(
                                onOpenDocumentClick = {
                                    openDocumentLauncher.launch(arrayOf("application/pdf"))
                                }
                            )
                        }
                        composable("viewer") {
                            PdfViewerScreen(
                                state = state,
                                onPageChanged = { viewModel.loadPage(it) },
                                onTextBlockClick = { viewModel.selectTextBlock(it) },
                                onExpandSelection = { viewModel.expandSelection(it) },
                                onConfirmEdit = { viewModel.confirmEdit(it) },
                                onDismissEdit = { viewModel.selectTextBlock(null) },
                                onUndoClick = { viewModel.undo() },
                                onRedoClick = { viewModel.redo() },
                                onExportClick = {
                                    createDocumentLauncher.launch("edited_document.pdf")
                                },
                                onBackClick = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }

                    // Observe state transitions to handle screen navigation reacting to loading document
                    SideEffectNavigation(
                        state = state,
                        onNavigateToViewer = {
                            if (navController.currentDestination?.route != "viewer") {
                                navController.navigate("viewer") {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// TODO: Replace with LaunchedEffect + NavController observer for cleaner navigation handling
// TODO: Add animated transition between home and viewer screens
@Composable
fun SideEffectNavigation(
    state: com.genx.ai.photo.editpdf.presentation.state.PdfViewerState,
    onNavigateToViewer: () -> Unit
) {
    // If a document has been successfully opened, trigger navigation to viewer screen
    if (state.pdfDocument != null && !state.isLoading && state.renderedBitmap != null) {
        androidx.compose.runtime.LaunchedEffect(state.pdfDocument) {
            onNavigateToViewer()
        }
    }
}