package ru.argentoz;

/**
 * Port of stb_rect_pack.h v1.01 (Sean Barrett, public domain) to Java 21.
 * <p>
 * Skyline Bottom-Left / Best-Fit rectangle packer for texture atlas generation.
 * Zero per-pack heap allocations after {@link #initTarget} — all internal
 * storage is array-based and reused across calls.
 * <p>
 * Original C version: https://github.com/nothings/stb
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * var packer = new StbRectPack();
 * packer.initTarget(1024, 1024, 1024);      // width, height, numNodes (>= width recommended)
 * packer.setHeuristic(StbRectPack.HEURISTIC_BF); // optional, BL is default
 *
 * int[] ids = {0, 1, 2};
 * int[] ws  = {64, 128, 32};
 * int[] hs  = {64, 64,  48};
 * int[] outX = new int[3], outY = new int[3];
 * boolean[] packed = new boolean[3];
 *
 * boolean allPacked = packer.packRects(ids, ws, hs, outX, outY, packed, 3);
 * }</pre>
 */
public final class STBRectPack {

    public static final int MAXVAL = 0x7fff_ffff;

    /** Bottom-Left heuristic (default). Faster, slightly worse packing. */
    public static final int HEURISTIC_BL = 0;
    /** Best-Fit heuristic. ~2× slower, generally better packing density. */
    public static final int HEURISTIC_BF = 1;

    // ── node pool (parallel arrays, indices 0 .. totalNodes-1) ──────────
    private int[] nodeX;
    private int[] nodeY;
    private int[] nodeNext;   // -1 ≡ null

    // ── context ─────────────────────────────────────────────────────────
    private int width;
    private int height;
    private int align;
    private int heuristic;
    private int numNodes;     // user-requested count (extra[2] live at numNodes, numNodes+1)
    private int activeHead;   // index into node arrays, -1 ≡ null
    private int freeHead;

    // ── reusable scratch for packRects ──────────────────────────────────
    private int[] sortIdx;    // indirection array for sorting

    // ── "output parameters" kept as fields to avoid allocation ──────────
    private int _waste;       // out-param of skylineFindMinY
    private int _frX, _frY;  // out of skylineFindBestPos
    private int _frPrev;      // out of skylineFindBestPos (encoded prev-link)

    // prev-link encoding:
    //   PREV_NULL         → not found
    //   PREV_ACTIVE_HEAD  → the link is 'activeHead' itself
    //   >= 0              → the link is 'nodeNext[value]'
    private static final int PREV_NULL        = -2;
    private static final int PREV_ACTIVE_HEAD = -1;

    // ────────────────────────────────────────────────────────────────────
    //  Public API
    // ────────────────────────────────────────────────────────────────────

    /**
     * Initialise (or reinitialise) the packer for a target rectangle.
     *
     * @param width    target width in pixels
     * @param height   target height in pixels
     * @param numNodes number of working nodes; use {@code >= width} for best results
     */
    public void initTarget(int width, int height, int numNodes) {
        this.width     = width;
        this.height    = height;
        this.numNodes  = numNodes;
        this.heuristic = HEURISTIC_BL;

        int total = numNodes + 2;
        if (nodeX == null || nodeX.length < total) {
            nodeX    = new int[total];
            nodeY    = new int[total];
            nodeNext = new int[total];
        }

        // build free list: 0 → 1 → … → numNodes-1 → -1
        for (int i = 0; i < numNodes - 1; i++) nodeNext[i] = i + 1;
        nodeNext[numNodes - 1] = -1;
        freeHead = 0;

        // sentinel nodes (extra[0], extra[1])
        int ex0 = numNodes, ex1 = numNodes + 1;
        nodeX[ex0] = 0;          nodeY[ex0] = 0;          nodeNext[ex0] = ex1;
        nodeX[ex1] = width;      nodeY[ex1] = 1 << 30;    nodeNext[ex1] = -1;
        activeHead = ex0;

        setupAllowOutOfMem(false);
    }

    /** Select packing heuristic: {@link #HEURISTIC_BL} or {@link #HEURISTIC_BF}. */
    public void setHeuristic(int heuristic) {
        this.heuristic = heuristic;
    }

    /**
     * If {@code allow == true} widths are not quantised; packing is tighter but
     * may fail if the node pool is exhausted.
     */
    public void setupAllowOutOfMem(boolean allow) {
        align = allow ? 1 : (width + numNodes - 1) / numNodes;
    }

    /**
     * Pack rectangles into the target.  All arrays must have length {@code >= count}.
     * <p>
     * Uses SoA (struct-of-arrays) layout to avoid creating per-rect objects.
     *
     * @param ids     user ids (untouched, carried through for caller convenience)
     * @param ws      input widths
     * @param hs      input heights
     * @param outX    receives packed X (or {@link #MAXVAL} on failure)
     * @param outY    receives packed Y (or {@link #MAXVAL} on failure)
     * @param packed  receives per-rect success flag
     * @param count   number of rectangles
     * @return {@code true} if every rectangle was placed
     */
    public boolean packRects(int[] ids, int[] ws, int[] hs,
                             int[] outX, int[] outY, boolean[] packed,
                             int count) {
        // build / reuse indirection array
        if (sortIdx == null || sortIdx.length < count) {
            sortIdx = new int[count];
        }
        for (int i = 0; i < count; i++) sortIdx[i] = i;

        // sort indices by height desc, then width desc  (no boxing, no Comparator)
        quickSort(sortIdx, ws, hs, 0, count - 1);

        boolean allPacked = true;
        for (int i = 0; i < count; i++) {
            int ri = sortIdx[i];
            int w = ws[ri], h = hs[ri];
            if (w == 0 || h == 0) {
                outX[ri] = 0;
                outY[ri] = 0;
                packed[ri] = true;
            } else if (skylinePackRect(w, h)) {
                outX[ri] = _frX;
                outY[ri] = _frY;
                packed[ri] = true;
            } else {
                outX[ri] = MAXVAL;
                outY[ri] = MAXVAL;
                packed[ri] = false;
                allPacked = false;
            }
        }
        return allPacked;
    }

    /**
     * Convenience overload that works with a plain {@code Rect} record array.
     * Allocates nothing beyond the first call (reuses internal scratch).
     */
    public boolean packRects(Rect[] rects, int count) {
        if (sortIdx == null || sortIdx.length < count) {
            sortIdx = new int[count];
        }
        for (int i = 0; i < count; i++) sortIdx[i] = i;

        quickSortRects(sortIdx, rects, 0, count - 1);

        boolean allPacked = true;
        for (int i = 0; i < count; i++) {
            int ri = sortIdx[i];
            Rect r = rects[ri];
            if (r.w == 0 || r.h == 0) {
                r.x = 0; r.y = 0; r.wasPacked = true;
            } else if (skylinePackRect(r.w, r.h)) {
                r.x = _frX; r.y = _frY; r.wasPacked = true;
            } else {
                r.x = MAXVAL; r.y = MAXVAL; r.wasPacked = false;
                allPacked = false;
            }
        }
        return allPacked;
    }

    /** Simple mutable rectangle — mirrors the C {@code stbrp_rect}. */
    public static final class Rect {
        public int id;
        public int w, h;      // input
        public int x, y;      // output
        public boolean wasPacked;
    }

    // ────────────────────────────────────────────────────────────────────
    //  Internals
    // ────────────────────────────────────────────────────────────────────

    private int readLink(int prev) {
        return prev == PREV_ACTIVE_HEAD ? activeHead : nodeNext[prev];
    }

    private void writeLink(int prev, int value) {
        if (prev == PREV_ACTIVE_HEAD) activeHead = value;
        else                          nodeNext[prev] = value;
    }

    /** Find minimum Y at position {@code x0} spanning {@code w} pixels. Sets {@link #_waste}. */
    private int skylineFindMinY(int first, int x0, int w) {
        int node = first;
        int x1 = x0 + w;
        int minY = 0, waste = 0, visited = 0;

        while (nodeX[node] < x1) {
            int ny = nodeY[node];
            int nx = nodeX[node];
            int nnx = nodeX[nodeNext[node]];
            if (ny > minY) {
                waste += visited * (ny - minY);
                minY = ny;
                visited += (nx < x0) ? (nnx - x0) : (nnx - nx);
            } else {
                int under = nnx - nx;
                if (under + visited > w) under = w - visited;
                waste += under * (minY - ny);
                visited += under;
            }
            node = nodeNext[node];
        }
        _waste = waste;
        return minY;
    }

    /**
     * Find the best position for a rectangle of size {@code w×h}.
     * Sets {@link #_frX}, {@link #_frY}, {@link #_frPrev}.
     */
    private void skylineFindBestPos(int w, int h) {
        int bestWaste = 1 << 30, bestY = 1 << 30, bestX = 0;
        int bestPrev = PREV_NULL;

        // align width
        w = w + align - 1;
        w -= w % align;

        if (w > width || h > height) {
            _frPrev = PREV_NULL; _frX = 0; _frY = 0;
            return;
        }

        // ── left-aligned pass (both BL and BF) ──
        int node = activeHead;
        int prev = PREV_ACTIVE_HEAD;
        while (nodeX[node] + w <= width) {
            int y = skylineFindMinY(node, nodeX[node], w);
            int waste = _waste;
            if (heuristic == HEURISTIC_BL) {
                if (y < bestY) {
                    bestY = y;
                    bestPrev = prev;
                }
            } else {
                if (y + h <= height) {
                    if (y < bestY || (y == bestY && waste < bestWaste)) {
                        bestY = y;
                        bestWaste = waste;
                        bestPrev = prev;
                    }
                }
            }
            prev = node;
            node = nodeNext[node];
        }

        bestX = (bestPrev == PREV_NULL) ? 0 : nodeX[readLink(bestPrev)];

        // ── right-aligned pass (BF only) ──
        if (heuristic == HEURISTIC_BF) {
            int tail = activeHead;
            node = activeHead;
            prev = PREV_ACTIVE_HEAD;
            while (nodeX[tail] < w) tail = nodeNext[tail];

            while (tail != -1) {
                int xpos = nodeX[tail] - w;
                // advance node/prev so that node covers xpos
                while (nodeX[nodeNext[node]] <= xpos) {
                    prev = node;
                    node = nodeNext[node];
                }
                int y = skylineFindMinY(node, xpos, w);
                int waste = _waste;
                if (y + h <= height && y <= bestY) {
                    if (y < bestY || waste < bestWaste || (waste == bestWaste && xpos < bestX)) {
                        bestX = xpos;
                        bestY = y;
                        bestWaste = waste;
                        bestPrev = prev;
                    }
                }
                tail = nodeNext[tail];
            }
        }

        _frPrev = bestPrev;
        _frX    = bestX;
        _frY    = bestY;
    }

    /**
     * Try to pack one rectangle. Returns {@code true} on success;
     * results in {@link #_frX}, {@link #_frY}.
     */
    private boolean skylinePackRect(int w, int h) {
        skylineFindBestPos(w, h);

        if (_frPrev == PREV_NULL || _frY + h > height || freeHead == -1) {
            _frPrev = PREV_NULL;
            return false;
        }

        // allocate node from free list
        int node = freeHead;
        nodeX[node] = _frX;
        nodeY[node] = _frY + h;
        freeHead = nodeNext[node];

        // insert into active list
        int cur = readLink(_frPrev);
        if (nodeX[cur] < _frX) {
            int next = nodeNext[cur];
            nodeNext[cur] = node;
            cur = next;
        } else {
            writeLink(_frPrev, node);
        }

        // reclaim nodes fully covered by the new rectangle
        int right = _frX + w;
        while (nodeNext[cur] != -1 && nodeX[nodeNext[cur]] <= right) {
            int next = nodeNext[cur];
            nodeNext[cur] = freeHead;
            freeHead = cur;
            cur = next;
        }

        // stitch the list
        nodeNext[node] = cur;
        if (nodeX[cur] < right) nodeX[cur] = right;

        return true;
    }

    // ── allocation-free quicksort (SoA variant) ─────────────────────────

    /** Sort {@code idx} by (hs desc, ws desc). */
    private static void quickSort(int[] idx, int[] ws, int[] hs, int lo, int hi) {
        while (lo < hi) {
            int p = partition(idx, ws, hs, lo, hi);
            // recurse into the smaller partition, iterate the larger
            if (p - lo < hi - p) {
                quickSort(idx, ws, hs, lo, p - 1);
                lo = p + 1;
            } else {
                quickSort(idx, ws, hs, p + 1, hi);
                hi = p - 1;
            }
        }
    }

    private static int partition(int[] idx, int[] ws, int[] hs, int lo, int hi) {
        int mid = lo + ((hi - lo) >>> 1);
        // median-of-three pivot
        if (compare(hs, ws, idx[lo], idx[mid]) > 0) swap(idx, lo, mid);
        if (compare(hs, ws, idx[lo], idx[hi])  > 0) swap(idx, lo, hi);
        if (compare(hs, ws, idx[mid], idx[hi]) > 0) swap(idx, mid, hi);
        swap(idx, mid, hi); // pivot at hi
        int pivotIdx = idx[hi];
        int i = lo - 1;
        for (int j = lo; j < hi; j++) {
            if (compare(hs, ws, idx[j], pivotIdx) <= 0) {
                swap(idx, ++i, j);
            }
        }
        swap(idx, i + 1, hi);
        return i + 1;
    }

    /** Compare two rect indices: height desc, width desc. Negative ⇒ a should come first. */
    private static int compare(int[] hs, int[] ws, int a, int b) {
        int dh = hs[b] - hs[a];
        if (dh != 0) {
            return dh;
        }
        int dw = ws[b] - ws[a];
        if (dw != 0) {
            return dw;
        }
        return a - b;
    }

    private static void swap(int[] arr, int i, int j) {
        int t = arr[i]; arr[i] = arr[j]; arr[j] = t;
    }

    // ── allocation-free quicksort (Rect[] variant) ──────────────────────

    private static void quickSortRects(int[] idx, Rect[] rects, int lo, int hi) {
        while (lo < hi) {
            int p = partitionRects(idx, rects, lo, hi);
            if (p - lo < hi - p) {
                quickSortRects(idx, rects, lo, p - 1);
                lo = p + 1;
            } else {
                quickSortRects(idx, rects, p + 1, hi);
                hi = p - 1;
            }
        }
    }

    private static int partitionRects(int[] idx, Rect[] rects, int lo, int hi) {
        int mid = lo + ((hi - lo) >>> 1);
        if (compareRects(rects, idx[lo], idx[mid]) > 0) swap(idx, lo, mid);
        if (compareRects(rects, idx[lo], idx[hi])  > 0) swap(idx, lo, hi);
        if (compareRects(rects, idx[mid], idx[hi]) > 0) swap(idx, mid, hi);
        swap(idx, mid, hi);
        int pivotIdx = idx[hi];
        int i = lo - 1;
        for (int j = lo; j < hi; j++) {
            if (compareRects(rects, idx[j], pivotIdx) <= 0) {
                swap(idx, ++i, j);
            }
        }
        swap(idx, i + 1, hi);
        return i + 1;
    }

    private static int compareRects(Rect[] rects, int a, int b) {
        int dh = rects[b].h - rects[a].h;
        if (dh != 0) {
            return dh;
        }
        int dw = rects[b].w - rects[a].w;
        if (dw != 0) {
            return dw;
        }
        return rects[a].id - rects[b].id;
    }
}
