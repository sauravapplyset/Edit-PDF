package com.genx.ai.photo.editpdf.presentation.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.genx.ai.photo.editpdf.domain.model.TextBlock
import com.genx.ai.photo.editpdf.presentation.state.PdfViewerState
import com.genx.ai.photo.editpdf.ui.theme.NeonCyan
import com.genx.ai.photo.editpdf.ui.theme.SelectionBorderColor
import kotlin.math.abs

// TODO: Add a bottom sheet panel for page thumbnail strip navigation
// TODO: Add a floating action button for adding new text boxes to blank areas
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(
    state: PdfViewerState,
    onPageChanged: (Int) -> Unit,
    onTextBlockClick: (TextBlock) -> Unit,
    onConfirmEdit: (String) -> Unit,
    onDismissEdit: () -> Unit,
    onUndoClick: () -> Unit,
    onRedoClick: () -> Unit,
    onExportClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Zoom scale (1f = fit-to-screen, max 5x) and pan offset in px.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    // TODO: Clamp offset so page cannot be panned completely out of the visible area

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Edit PDF",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onUndoClick,
                        enabled = state.canUndo
                    ) {
                        Icon(
                            // AutoMirrored for proper RTL support
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "Undo",
                            tint = if (state.canUndo) NeonCyan
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = onRedoClick,
                        enabled = state.canRedo
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "Redo",
                            tint = if (state.canRedo) NeonCyan
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(onClick = onExportClick) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Export PDF",
                            tint = NeonCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            state.pdfDocument?.let { doc ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onPageChanged(state.currentPageIndex - 1) },
                        enabled = state.currentPageIndex > 0
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Previous Page",
                            tint = if (state.currentPageIndex > 0) NeonCyan
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Page ${state.currentPageIndex + 1} of ${doc.pageCount}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    IconButton(
                        onClick = { onPageChanged(state.currentPageIndex + 1) },
                        enabled = state.currentPageIndex < doc.pageCount - 1
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Next Page",
                            tint = if (state.currentPageIndex < doc.pageCount - 1) NeonCyan
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            if (state.isLoading) {
                CircularProgressIndicator(color = NeonCyan)
            } else {
                state.renderedBitmap?.let { bitmap ->
                    val page = state.pdfDocument?.pages?.getOrNull(state.currentPageIndex)
                    if (page != null) {

                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            // Container size in px (for coordinate inverse math)
                            val containerWidthPx = constraints.maxWidth.toFloat()
                            val containerHeightPx = constraints.maxHeight.toFloat()
                            val density = LocalDensity.current
                            val dpToPx = with(density) { 1.dp.toPx() }

                            // Compute page display size in dp, fitting the page aspect ratio
                            val pageAspectRatio = page.widthPt / page.heightPt
                            val containerWidthDp = maxWidth.value
                            val containerHeightDp = maxHeight.value
                            val containerAspectRatio = containerWidthDp / containerHeightDp

                            val displayWidthDp: Float
                            val displayHeightDp: Float
                            if (pageAspectRatio > containerAspectRatio) {
                                displayWidthDp = containerWidthDp
                                displayHeightDp = containerWidthDp / pageAspectRatio
                            } else {
                                displayHeightDp = containerHeightDp
                                displayWidthDp = containerHeightDp * pageAspectRatio
                            }

                            // Conversion factor: dp per PDF user-space pt
                            val scalePtToDp = displayWidthDp / page.widthPt

                            // Page top-left in container-local px BEFORE the graphicsLayer transform.
                            // (Container is center-aligned, so the page is offset by half the gap.)
                            val pageOriginX = (containerWidthPx - displayWidthDp * dpToPx) / 2f
                            val pageOriginY = (containerHeightPx - displayHeightDp * dpToPx) / 2f

                            // -----------------------------------------------------------------------
                            // UNIFIED POINTER INPUT — handles BOTH pinch-zoom/pan AND taps.
                            //
                            // BUG FIX: the original code used detectTransformGestures which
                            // greedily consumed every pointer event (including taps), so no
                            // clickable child ever received a touch event.
                            //
                            // FIX STRATEGY: awaitEachGesture + manual slop check:
                            //   • If the finger never crosses touchSlop → TAP → find the text block.
                            //   • If it crosses slop with 1 finger  → PAN.
                            //   • If it crosses slop with 2 fingers → PINCH-ZOOM.
                            //
                            // NOTE: scale/offset are NOT in the pointerInput key. They are mutable
                            // state values captured by reference inside the lambda.  Including them
                            // as keys would restart the handler on every recompose (every frame
                            // during pan), breaking mid-gesture detection.
                            // -----------------------------------------------------------------------
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(
                                        // Re-create handler only when the page or its layout changes,
                                        // not when scale/offset changes (those are captured by ref).
                                        state.textBlocks,
                                        displayWidthDp,
                                        displayHeightDp
                                    ) {
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            var accumulatedZoom = 1f
                                            var accumulatedPan = Offset.Zero
                                            var pastSlop = false
                                            val touchSlop = viewConfiguration.touchSlop

                                            do {
                                                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                                                // If a child consumed the event, defer to it
                                                if (event.changes.any { it.isConsumed }) break

                                                val zoomChange = event.calculateZoom()
                                                val panChange = event.calculatePan()
                                                accumulatedZoom *= zoomChange
                                                accumulatedPan += panChange

                                                if (!pastSlop) {
                                                    // Determine whether movement qualifies as a gesture
                                                    val centroidSize =
                                                        event.calculateCentroidSize(useCurrent = false)
                                                    val zoomMotion = abs(1 - accumulatedZoom) * centroidSize
                                                    val panMotion = accumulatedPan.getDistance()
                                                    if (zoomMotion > touchSlop || panMotion > touchSlop) {
                                                        pastSlop = true
                                                    }
                                                }

                                                if (pastSlop) {
                                                    // Apply transform and consume events
                                                    val centroid =
                                                        event.calculateCentroid(useCurrent = false)
                                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                                        val newScale =
                                                            (scale * zoomChange).coerceIn(1f, 5f)
                                                        val newOffset = if (newScale == 1f) {
                                                            Offset.Zero
                                                        } else {
                                                            // Zoom toward the pinch centroid
                                                            val scaleFactor = newScale / scale
                                                            offset + panChange +
                                                                (centroid - centroid * scaleFactor)
                                                        }
                                                        scale = newScale
                                                        offset = newOffset
                                                    }
                                                    event.changes.forEach { change ->
                                                        if (change.positionChanged()) change.consume()
                                                    }
                                                }
                                            } while (event.changes.any { it.pressed })

                                            // -------------------------------------------------------
                                            // TAP PATH — finger lifted without crossing slop
                                            // -------------------------------------------------------
                                            if (!pastSlop) {
                                                val tapPos = down.position

                                                // Invert the graphicsLayer transform to get the
                                                // unscaled/untranslated container-local position.
                                                //
                                                // graphicsLayer applies:
                                                //   1. Translate to center  (TransformOrigin 0.5,0.5)
                                                //   2. Scale by `scale`
                                                //   3. Translate by `offset`
                                                //
                                                // Inverse:
                                                //   screenPt = center + (layoutPt - center) * scale + offset
                                                //   layoutPt = center + (screenPt - center - offset) / scale
                                                val cx = containerWidthPx / 2f
                                                val cy = containerHeightPx / 2f
                                                val layoutX =
                                                    cx + (tapPos.x - cx - offset.x) / scale
                                                val layoutY =
                                                    cy + (tapPos.y - cy - offset.y) / scale

                                                // Page-local position in px (origin = page top-left)
                                                val pageLocalPxX = layoutX - pageOriginX
                                                val pageLocalPxY = layoutY - pageOriginY

                                                // Convert px → PDF user-space points
                                                val ptX = pageLocalPxX / dpToPx / scalePtToDp
                                                val ptY = pageLocalPxY / dpToPx / scalePtToDp

                                                // Hit-test: find the smallest text block that
                                                // contains the tapped PDF-space point.
                                                // A 4pt tolerance makes it forgiving without
                                                // making nearby blocks ambiguous.
                                                // TODO: Pick smallest block when multiple overlap
                                                val tolerance = 4f
                                                val tappedBlock = state.textBlocks
                                                    .filter { block ->
                                                        val bb = block.boundingBox
                                                        ptX >= bb.left - tolerance &&
                                                            ptX <= bb.right + tolerance &&
                                                            ptY >= bb.top - tolerance &&
                                                            ptY <= bb.bottom + tolerance
                                                    }
                                                    .minByOrNull { block ->
                                                        // Prefer the smallest block (most specific hit)
                                                        block.boundingBox.width * block.boundingBox.height
                                                    }
                                                tappedBlock?.let { onTextBlockClick(it) }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                // ---------------------------------------------------------------
                                // PAGE RENDER BOX
                                // Explicit size so graphicsLayer, Image and overlay all agree.
                                // Pointer events are handled by the parent Box above — this Box
                                // is layout + visual only and has no pointerInput modifier.
                                // ---------------------------------------------------------------
                                Box(
                                    modifier = Modifier
                                        .size(
                                            width = displayWidthDp.dp,
                                            height = displayHeightDp.dp
                                        )
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offset.x,
                                            translationY = offset.y,
                                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                                        )
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White)
                                ) {
                                    // Render the PDF page as a full-size bitmap
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "PDF Page View",
                                        modifier = Modifier.fillMaxSize()
                                    )

                                    // Draw subtle text-block outlines for visual feedback.
                                    // Click handling is NOT done here — it is done via the
                                    // coordinate-inversion hit test in the outer pointerInput.
                                    // Using drawWithContent avoids nested clickable boxes
                                    // which could interfere with gesture propagation.
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .drawWithContent {
                                                drawContent()
                                                state.textBlocks.forEach { block ->
                                                    val bb = block.boundingBox
                                                    val leftPx = bb.left * scalePtToDp * dpToPx
                                                    val topPx = bb.top * scalePtToDp * dpToPx
                                                    val widthPx = bb.width * scalePtToDp * dpToPx
                                                    val heightPx = bb.height * scalePtToDp * dpToPx
                                                    // Subtle outline — visible enough to confirm
                                                    // what's tappable, not distracting during reading
                                                    drawRect(
                                                        color = SelectionBorderColor.copy(alpha = 0.20f),
                                                        topLeft = Offset(leftPx, topPx),
                                                        size = Size(widthPx, heightPx),
                                                        style = Stroke(width = 1.5f)
                                                    )
                                                }
                                            }
                                    )
                                }
                            }
                        }
                    }
                } ?: Text(
                    text = "No page loaded.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            // -----------------------------------------------------------------------
            // TEXT EDITING DIALOG
            // TODO: Add font size slider so user can increase/decrease font size
            // TODO: Add a color picker row to change text color inline
            // TODO: Preview edited text in the original PDF font (not system default)
            // -----------------------------------------------------------------------
            state.selectedTextBlock?.let { block ->
                var textValue by remember(block) { mutableStateOf(block.text) }

                AlertDialog(
                    onDismissRequest = onDismissEdit,
                    title = {
                        Text(
                            text = "Edit Text Run",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    text = {
                        Column {
                            Text(
                                text = "Font: ${block.fontInfo.fontName} (Size: ${block.fontInfo.fontSize}pt)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            OutlinedTextField(
                                value = textValue,
                                onValueChange = { textValue = it },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonCyan,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(
                                        alpha = 0.2f
                                    ),
                                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { onConfirmEdit(textValue) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = MaterialTheme.colorScheme.background
                            ),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Text(text = "Apply", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissEdit) {
                            Text(
                                text = "Cancel",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(24.dp)
                )
            }

            // Error Message Dialog
            state.errorMessage?.let { error ->
                AlertDialog(
                    onDismissRequest = onDismissEdit,
                    title = {
                        Text(
                            text = "Error",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    text = {
                        Text(text = error, color = MaterialTheme.colorScheme.onSurface)
                    },
                    confirmButton = {
                        Button(
                            onClick = onDismissEdit,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("OK")
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}
