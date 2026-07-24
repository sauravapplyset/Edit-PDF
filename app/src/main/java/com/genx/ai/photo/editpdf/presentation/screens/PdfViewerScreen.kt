package com.genx.ai.photo.editpdf.presentation.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
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
    onExpandSelection: (com.genx.ai.photo.editpdf.domain.model.PdfRect) -> Unit,
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

    var textValue by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var isEditing by remember { mutableStateOf(false) }
    var isEditMode by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(state.selectedTextBlock) {
        if (state.selectedTextBlock != null) {
            val text = state.selectedTextBlock.text
            textValue = androidx.compose.ui.text.input.TextFieldValue(
                text = text,
                selection = androidx.compose.ui.text.TextRange(text.length)
            )
            isEditing = false
        } else {
            isEditing = false
        }
    }

    Scaffold(
        topBar = {
            if (state.selectedTextBlock != null && isEditing) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Editing Text",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismissEdit) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            onConfirmEdit(textValue.text)
                        }) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Save",
                                tint = NeonCyan
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            } else {
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
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF8F9FA))
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isEditMode) Color(0xFFE8F0FE) else Color.Transparent)
                        .clickable { isEditMode = !isEditMode }
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit text",
                        tint = if (isEditMode) Color(0xFF1A73E8) else Color.DarkGray,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Edit text",
                        fontSize = 12.sp,
                        color = if (isEditMode) Color(0xFF1A73E8) else Color.DarkGray,
                        fontWeight = if (isEditMode) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
                
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { /* TODO: Insert Text */ }
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "T+",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Insert text",
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { /* TODO: Insert Images */ }
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Insert Images",
                        tint = Color.DarkGray,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Insert Images",
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )
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
            val pageCount = state.pdfDocument?.pageCount ?: 1
            val pagerState = rememberPagerState(
                initialPage = state.currentPageIndex,
                pageCount = { pageCount }
            )

            androidx.compose.runtime.LaunchedEffect(pagerState) {
                androidx.compose.runtime.snapshotFlow { pagerState.currentPage }.collect { page ->
                    if (page != state.currentPageIndex) {
                        onPageChanged(page)
                    }
                }
            }

            androidx.compose.runtime.LaunchedEffect(state.currentPageIndex) {
                if (pagerState.currentPage != state.currentPageIndex) {
                    pagerState.animateScrollToPage(state.currentPageIndex)
                }
                scale = 1f
                offset = Offset.Zero
            }

            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = scale == 1f
            ) { pageIndex ->
                if (pageIndex == state.currentPageIndex) {
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

                            val imeBottomPx = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density)
                            
                            androidx.compose.runtime.LaunchedEffect(isEditing, imeBottomPx) {
                                if (isEditing && imeBottomPx > 0 && state.selectedTextBlock != null) {
                                    val block = state.selectedTextBlock!!
                                    val bb = block.boundingBox
                                    val pageLocalBottomY = (bb.top + bb.height) * scalePtToDp * dpToPx
                                    val unscaledY = pageOriginY + pageLocalBottomY
                                    val cy = containerHeightPx / 2f
                                    val screenY = cy + (unscaledY - cy) * scale + offset.y
                                    val visibleBottom = containerHeightPx - imeBottomPx - 200f // 200px padding for keyboard toolbar
                                    
                                    if (screenY > visibleBottom) {
                                        val panAmount = screenY - visibleBottom
                                        offset = offset.copy(y = offset.y - panAmount)
                                    }
                                }
                            }

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
                                            var wasConsumed = false
                                            val touchSlop = viewConfiguration.touchSlop

                                            do {
                                                val event = awaitPointerEvent(pass = PointerEventPass.Main)
                                                // If a child consumed the event, defer to it
                                                if (event.changes.any { it.isConsumed }) {
                                                    wasConsumed = true
                                                    break
                                                }

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
                                                        if (change.positionChanged() && scale > 1f) change.consume()
                                                    }
                                                }
                                            } while (event.changes.any { it.pressed })

                                            // -------------------------------------------------------
                                            // TAP PATH — finger lifted without crossing slop
                                            // -------------------------------------------------------
                                            if (!pastSlop && !wasConsumed) {
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
                                                if (tappedBlock != null) {
                                                    onTextBlockClick(tappedBlock)
                                                } else {
                                                    onDismissEdit()
                                                }
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

                                    // DASHED BORDERS FOR ALL TEXT BLOCKS WHEN EDIT MODE IS ON
                                    if (isEditMode) {
                                        state.textBlocks.forEach { block ->
                                            if (block != state.selectedTextBlock) {
                                                val bb = block.boundingBox
                                                val bLeft = (bb.left * scalePtToDp).dp
                                                val bTop = (bb.top * scalePtToDp).dp
                                                val bWidth = (bb.width * scalePtToDp).dp
                                                val bHeight = (bb.height * scalePtToDp).dp
                                                
                                                Box(
                                                    modifier = Modifier
                                                        .offset(x = bLeft, y = bTop)
                                                        .size(width = bWidth, height = bHeight)
                                                        .drawWithContent {
                                                            drawContent()
                                                            drawRect(
                                                                color = Color.Gray.copy(alpha = 0.5f),
                                                                style = Stroke(
                                                                    width = 2.dp.toPx(),
                                                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                                                )
                                                            )
                                                        }
                                                )
                                            }
                                        }
                                    }

                                    // INLINE EDIT OVERLAY / SELECTION
                                    state.selectedTextBlock?.let { block ->
                                        val bb = block.boundingBox
                                        val leftDp = (bb.left * scalePtToDp).dp
                                        val topDp = (bb.top * scalePtToDp).dp
                                        val widthDp = (bb.width * scalePtToDp).dp
                                        val heightDp = (bb.height * scalePtToDp).dp
                                        val fontSizeSp = (block.fontInfo.fontSize * scalePtToDp)

                                        if (!isEditing) {
                                            // Local state for dragging the bounding box
                                            var dragLeft by remember(block.id) { androidx.compose.runtime.mutableStateOf(leftDp.value) }
                                            var dragTop by remember(block.id) { androidx.compose.runtime.mutableStateOf(topDp.value) }
                                            var dragRight by remember(block.id) { androidx.compose.runtime.mutableStateOf((leftDp + widthDp).value) }
                                            var dragBottom by remember(block.id) { androidx.compose.runtime.mutableStateOf((topDp + heightDp).value) }
                                            var isDragging by remember(block.id) { androidx.compose.runtime.mutableStateOf(false) }

                                            val currentLeftDp = if (isDragging) dragLeft.dp else leftDp
                                            val currentTopDp = if (isDragging) dragTop.dp else topDp
                                            val currentWidthDp = if (isDragging) (dragRight - dragLeft).dp else widthDp
                                            val currentHeightDp = if (isDragging) (dragBottom - dragTop).dp else heightDp

                                            val handleSize = 14.dp
                                            val handleOffset = handleSize / 2
                                            
                                            val densityVal = density.density

                                            // Premium Blue Selection Bounding Box with Elegant Handles
                                            Box(
                                                modifier = Modifier
                                                    .offset(x = currentLeftDp, y = currentTopDp)
                                                    .size(width = currentWidthDp, height = currentHeightDp)
                                                    .border(1.dp, Color(0xFF1A73E8).copy(alpha = 0.8f))
                                            ) {
                                                // Top Left Handle
                                                Box(modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .offset(x = -handleOffset, y = -handleOffset)
                                                    .size(handleSize)
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                                    .background(Color(0xFF1A73E8))
                                                    .border(1.5.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
                                                    .pointerInput(block.id) {
                                                        detectDragGestures(
                                                            onDragStart = { 
                                                                isDragging = true
                                                                dragLeft = leftDp.value
                                                                dragTop = topDp.value
                                                                dragRight = (leftDp + widthDp).value
                                                                dragBottom = (topDp + heightDp).value
                                                            },
                                                            onDragEnd = {
                                                                isDragging = false
                                                                val newRect = com.genx.ai.photo.editpdf.domain.model.PdfRect(
                                                                    dragLeft / scalePtToDp,
                                                                    dragTop / scalePtToDp,
                                                                    dragRight / scalePtToDp,
                                                                    dragBottom / scalePtToDp
                                                                )
                                                                onExpandSelection(newRect)
                                                            }
                                                        ) { change, dragAmount ->
                                                            change.consume()
                                                            dragLeft = (dragLeft + dragAmount.x / densityVal).coerceAtMost(dragRight - 10f)
                                                            dragTop = (dragTop + dragAmount.y / densityVal).coerceAtMost(dragBottom - 10f)
                                                        }
                                                    }
                                                )
                                                
                                                // Top Right Handle
                                                Box(modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = handleOffset, y = -handleOffset)
                                                    .size(handleSize)
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                                    .background(Color(0xFF1A73E8))
                                                    .border(1.5.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
                                                    .pointerInput(block.id) {
                                                        detectDragGestures(
                                                            onDragStart = { 
                                                                isDragging = true
                                                                dragLeft = leftDp.value
                                                                dragTop = topDp.value
                                                                dragRight = (leftDp + widthDp).value
                                                                dragBottom = (topDp + heightDp).value
                                                            },
                                                            onDragEnd = {
                                                                isDragging = false
                                                                val newRect = com.genx.ai.photo.editpdf.domain.model.PdfRect(
                                                                    dragLeft / scalePtToDp,
                                                                    dragTop / scalePtToDp,
                                                                    dragRight / scalePtToDp,
                                                                    dragBottom / scalePtToDp
                                                                )
                                                                onExpandSelection(newRect)
                                                            }
                                                        ) { change, dragAmount ->
                                                            change.consume()
                                                            dragRight = (dragRight + dragAmount.x / densityVal).coerceAtLeast(dragLeft + 10f)
                                                            dragTop = (dragTop + dragAmount.y / densityVal).coerceAtMost(dragBottom - 10f)
                                                        }
                                                    }
                                                )
                                                
                                                // Bottom Left Handle
                                                Box(modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .offset(x = -handleOffset, y = handleOffset)
                                                    .size(handleSize)
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                                    .background(Color(0xFF1A73E8))
                                                    .border(1.5.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
                                                    .pointerInput(block.id) {
                                                        detectDragGestures(
                                                            onDragStart = { 
                                                                isDragging = true
                                                                dragLeft = leftDp.value
                                                                dragTop = topDp.value
                                                                dragRight = (leftDp + widthDp).value
                                                                dragBottom = (topDp + heightDp).value
                                                            },
                                                            onDragEnd = {
                                                                isDragging = false
                                                                val newRect = com.genx.ai.photo.editpdf.domain.model.PdfRect(
                                                                    dragLeft / scalePtToDp,
                                                                    dragTop / scalePtToDp,
                                                                    dragRight / scalePtToDp,
                                                                    dragBottom / scalePtToDp
                                                                )
                                                                onExpandSelection(newRect)
                                                            }
                                                        ) { change, dragAmount ->
                                                            change.consume()
                                                            dragLeft = (dragLeft + dragAmount.x / densityVal).coerceAtMost(dragRight - 10f)
                                                            dragBottom = (dragBottom + dragAmount.y / densityVal).coerceAtLeast(dragTop + 10f)
                                                        }
                                                    }
                                                )
                                                
                                                // Bottom Right Handle
                                                Box(modifier = Modifier
                                                    .align(Alignment.BottomEnd)
                                                    .offset(x = handleOffset, y = handleOffset)
                                                    .size(handleSize)
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                                    .background(Color(0xFF1A73E8))
                                                    .border(1.5.dp, Color.White, androidx.compose.foundation.shape.CircleShape)
                                                    .pointerInput(block.id) {
                                                        detectDragGestures(
                                                            onDragStart = { 
                                                                isDragging = true
                                                                dragLeft = leftDp.value
                                                                dragTop = topDp.value
                                                                dragRight = (leftDp + widthDp).value
                                                                dragBottom = (topDp + heightDp).value
                                                            },
                                                            onDragEnd = {
                                                                isDragging = false
                                                                val newRect = com.genx.ai.photo.editpdf.domain.model.PdfRect(
                                                                    dragLeft / scalePtToDp,
                                                                    dragTop / scalePtToDp,
                                                                    dragRight / scalePtToDp,
                                                                    dragBottom / scalePtToDp
                                                                )
                                                                onExpandSelection(newRect)
                                                            }
                                                        ) { change, dragAmount ->
                                                            change.consume()
                                                            dragRight = (dragRight + dragAmount.x / densityVal).coerceAtLeast(dragLeft + 10f)
                                                            dragBottom = (dragBottom + dragAmount.y / densityVal).coerceAtLeast(dragTop + 10f)
                                                        }
                                                    }
                                                )
                                            }

                                            // Floating Menu (Edit, Copy, Delete)
                                            Box(
                                                modifier = Modifier
                                                    .offset(x = currentLeftDp, y = currentTopDp - 70.dp) // Moved slightly higher for larger menu
                                                    .background(Color.White, RoundedCornerShape(12.dp))
                                                    .border(1.dp, Color.LightGray.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        modifier = Modifier.clickable { isEditing = true }
                                                    ) {
                                                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.Black, modifier = Modifier.size(24.dp))
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text("Edit", fontSize = 12.sp, color = Color.Black)
                                                    }
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        modifier = Modifier.clickable { /* TODO: Copy */ }
                                                    ) {
                                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.Black, modifier = Modifier.size(24.dp))
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text("Copy", fontSize = 12.sp, color = Color.Black)
                                                    }
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        modifier = Modifier.clickable { /* TODO: Delete */ }
                                                    ) {
                                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Black, modifier = Modifier.size(24.dp))
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text("Delete", fontSize = 12.sp, color = Color.Black)
                                                    }
                                                }
                                            }
                                        } else {
                                            val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                                focusRequester.requestFocus()
                                            }

                                            val fontSizeSpVal = with(LocalDensity.current) { (block.fontInfo.fontSize * scalePtToDp).dp.toSp() }

                                            Box(
                                                modifier = Modifier
                                                    .offset(x = leftDp - 4.dp, y = topDp - 2.dp)
                                                    .defaultMinSize(minWidth = widthDp + 8.dp, minHeight = heightDp + 4.dp)
                                                    .background(Color.White, RoundedCornerShape(4.dp))
                                                    .border(1.dp, Color(0xFF1A73E8).copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 4.dp),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                androidx.compose.foundation.text.BasicTextField(
                                                    value = textValue,
                                                    onValueChange = { newValue ->
                                                        textValue = newValue
                                                    },
                                                    singleLine = false,
                                                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                                                    ),
                                                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                                        onDone = { onConfirmEdit(textValue.text) }
                                                    ),
                                                    modifier = Modifier
                                                        .widthIn(min = widthDp)
                                                        .focusRequester(focusRequester),
                                                    textStyle = androidx.compose.ui.text.TextStyle(
                                                        fontSize = fontSizeSpVal,
                                                        color = Color(0xFF1F1F1F) // slightly softer black for premium feel
                                                    ),
                                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF1A73E8))
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } ?: Text(
                    text = "No page loaded.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NeonCyan)
                    }
                }
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

            // Floating Page Indicator
            state.pdfDocument?.let { doc ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "${state.currentPageIndex + 1}/${doc.pageCount}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
