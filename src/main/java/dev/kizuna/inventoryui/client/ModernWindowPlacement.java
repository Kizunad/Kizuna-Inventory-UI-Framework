package dev.kizuna.inventoryui.client;

import static dev.kizuna.inventoryui.client.ModernMetrics.WINDOW_HEADER_HEIGHT;

import dev.kizuna.inventoryui.window.UiWindowManager.Rect;
import java.util.LinkedHashSet;
import java.util.List;

/** 新版展开窗的缺省落点，所有坐标均为宿主内的逻辑像素。 */
final class ModernWindowPlacement {
    private static final int GAP = 4;

    private ModernWindowPlacement() {}

    static Rect place(Rect preferred, Rect frame, List<Rect> obstacles) {
        // 窄屏上把第一个窄窗靠边，给下一个窗留下连续空间。
        int width = preferred.width();
        int left = preferred.x();
        if (left < width && width <= frame.width() / 2) {
            left = Math.min(GAP, frame.width() - width);
        }
        Rect origin = new Rect(left, preferred.y(), width, preferred.height());
        if (obstacles.isEmpty()) {
            return origin;
        }
        var xs = new LinkedHashSet<Integer>();
        var ys = new LinkedHashSet<Integer>();
        xs.add(left);
        ys.add(origin.y());
        for (var obstacle : obstacles) {
            // 障碍四边决定所有可用空隙；先试留缝的位置，刚好够宽时允许贴边。
            int right = obstacle.x() + obstacle.width();
            int bottom = obstacle.y() + obstacle.height();
            xs.add(right + GAP);
            xs.add(right);
            xs.add(obstacle.x() - width - GAP);
            xs.add(obstacle.x() - width);
            ys.add(bottom + GAP);
            ys.add(bottom);
            ys.add(obstacle.y() - origin.height());
            ys.add(obstacle.y() + WINDOW_HEADER_HEIGHT);
        }
        return closest(origin, frame, obstacles, xs, ys);
    }

    private static Rect closest(Rect origin, Rect frame, List<Rect> obstacles,
                                Iterable<Integer> xs, Iterable<Integer> ys) {
        Rect best = origin;
        Score score = score(origin, origin, obstacles);
        int right = frame.width() - origin.width();
        int bottom = frame.y() + frame.height();
        for (int x : xs) {
            int left = Math.clamp(x, 0, right);
            for (int y : ys) {
                int top = Math.clamp(y, frame.y(), bottom - origin.height());
                Rect full = new Rect(left, top, origin.width(), origin.height());
                Score candidate = score(full, origin, obstacles);
                if (candidate.betterThan(score)) {
                    best = full;
                    score = candidate;
                }
                // 整窗放不下且会挡标题时，可在旧标题下缩短正文，保留两个拖动入口。
                if (y > top && bottom - y >= WINDOW_HEADER_HEIGHT
                        && (score.headerOverlap() > 0 || score.lostHeight() > 0)) {
                    Rect shorter = new Rect(left, y, origin.width(), bottom - y);
                    Score clipped = score(shorter, origin, obstacles);
                    if (clipped.betterThan(score)) {
                        best = shorter;
                        score = clipped;
                    }
                }
            }
        }
        return best;
    }

    private static Score score(Rect bounds, Rect origin, List<Rect> obstacles) {
        long headerOverlap = 0;
        long overlap = 0;
        for (Rect other : obstacles) {
            // 新窗口在最上层，先保留旧标题，再尽量保留双方的正文。
            int width = intersection(bounds.x(), bounds.width(), other.x(), other.width());
            int height = intersection(bounds.y(), bounds.height(), other.y(), other.height());
            int header = Math.min(WINDOW_HEADER_HEIGHT, other.height());
            headerOverlap += (long) width * intersection(bounds.y(), bounds.height(), other.y(), header);
            overlap += (long) width * height;
        }
        int distance = Math.abs(bounds.x() - origin.x()) + Math.abs(bounds.y() - origin.y());
        return new Score(headerOverlap, origin.height() - bounds.height(), overlap, distance);
    }

    private static int intersection(int a, int lengthA, int b, int lengthB) {
        return Math.max(0, Math.min(a + lengthA, b + lengthB) - Math.max(a, b));
    }

    private record Score(long headerOverlap, int lostHeight, long overlap, int distance) {
        boolean betterThan(Score other) {
            // 能看见标题的完整窗口优先；同样大的候选再按遮盖面积和移动距离选。
            if (headerOverlap != other.headerOverlap) {
                return headerOverlap < other.headerOverlap;
            }
            if (lostHeight != other.lostHeight) {
                return lostHeight < other.lostHeight;
            }
            if (overlap != other.overlap) {
                return overlap < other.overlap;
            }
            return distance < other.distance;
        }
    }
}
