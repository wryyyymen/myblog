package com.syf.blog.game.engine;

import com.syf.blog.dto.game.GameMapResponse;
import com.syf.blog.exception.BusinessException;
import com.syf.blog.game.runtime.GameMatch;
import com.syf.blog.game.runtime.PlayerInput;
import com.syf.blog.game.runtime.TankState;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;

/**
 * 纯移动规则，无网络、数据库或定时器。调用方控制何时执行一个固定步长。
 * 输入只决定方向，位移始终使用服务端速度和步长，客户端不能提交坐标。
 */
public class GameMovementEngine {

    public static final long STEP_MILLIS = 50L;
    public static final long INPUT_TIMEOUT_MILLIS = 500L;
    public static final double MOVE_SPEED = 120.0;

    private static final int MAX_MAP_LENGTH = 128;
    private static final double TANK_TILE_RATIO = 0.8;
    private static final double EPSILON = 0.00000001;
    private static final int[] ROW_CHANGES = { -1, 1, 0, 0 };
    private static final int[] COLUMN_CHANGES = { 0, 0, -1, 1 };

    /** 验证地图并复制本局布局，在同一片可通行区域生成两辆互不重叠的坦克。 */
    public GameMatch createMatch(String roomId, String matchId, GameMapResponse sourceMap,
                                 List<Long> userIds) {
        if (userIds == null || userIds.size() != 2 || userIds.get(0) == null
                || userIds.get(1) == null || userIds.get(0) <= 0L || userIds.get(1) <= 0L
                || userIds.get(0).equals(userIds.get(1))) {
            throw invalidMap("开局需要两名不同的有效玩家");
        }

        GameMapResponse map = copyAndValidateMap(sourceMap);
        List<GridCell> component = findLargestComponent(map);
        if (component.size() < 2) {
            throw invalidMap("地图没有能够容纳两名玩家的连通区域");
        }

        // 两次广度搜索选出相距较远的位置，避免玩家出生在互不相通的孤岛。
        GridCell first = findFarthestCell(map, component.get(0));
        GridCell second = findFarthestCell(map, first);
        int columns = map.seed().get(0).size();
        if (cellOrder(first, columns) > cellOrder(second, columns)) {
            GridCell temporary = first;
            first = second;
            second = temporary;
        }

        double size = map.tileSize() * TANK_TILE_RATIO;
        List<TankState> tanks = new ArrayList<TankState>();
        TankState firstTank = createTank(userIds.get(0), size, first, map.tileSize(), 3);
        TankState secondTank = createTank(userIds.get(1), size, second, map.tileSize(), 2);
        tanks.add(firstTank);
        tanks.add(secondTank);

        GameMatch match = new GameMatch(roomId, matchId, map, List.copyOf(tanks));
        System.out.println("[GameMovementEngine.createMatch] matchId = " + matchId
                + "，地图尺寸 = " + columns + "×" + map.seed().size()
                + "，出生玩家 = " + userIds);
        return match;
    }

    /** 推进一个 50ms 步长，不使用客户端时间或客户端提供的移动距离。 */
    public void step(GameMatch match, long nowMillis) {
        double distance = MOVE_SPEED * STEP_MILLIS / 1000.0;
        for (TankState tank : match.getTanks()) {
            if (tank.getHp() <= 0) {
                continue;
            }
            PlayerInput input = match.getInputs().get(tank.getUserId());
            if (input == null || input.getSeq() < tank.getLastInputSeq()) {
                continue;
            }

            if (input.getSeq() > tank.getLastInputSeq()) {
                tank.setLastInputSeq(input.getSeq());
            }

            long ageMillis = nowMillis - input.getReceivedAtMillis();
            if (ageMillis < 0L || ageMillis > INPUT_TIMEOUT_MILLIS) {
                continue;
            }

            Integer direction = input.getDirection();
            if (direction == null || direction < 0 || direction > 3) {
                continue;
            }

            // 面向撞到的墙，位置仍由下面的碰撞计算限制。
            tank.setDirection(direction);
            moveTank(match, tank, direction, distance);
        }

        long nextTick = match.getTick() + 1L;
        match.setTick(nextTick);
    }

    /** 人类和 AI 共用扫掠碰撞；AI 只传入服务端计算的有限位移。 */
    public double moveTank(GameMatch match, TankState tank, int direction, double distance) {
        double allowedDistance = calculateAllowedDistance(match, tank, direction, distance);
        tank.setDirection(direction);
        if (direction == 0) tank.setY(tank.getY() - allowedDistance);
        else if (direction == 1) tank.setY(tank.getY() + allowedDistance);
        else if (direction == 2) tank.setX(tank.getX() - allowedDistance);
        else tank.setX(tank.getX() + allowedDistance);
        return allowedDistance;
    }

    /**
     * 对整个移动路径做轴对齐扫掠：算出前方障碍的最近间隙，再截短位移。
     * 不能只检查终点，否则以后提高速度时可能一次跳过一格墙。
     */
    private double calculateAllowedDistance(GameMatch match, TankState tank, int direction,
                                            double requestedDistance) {
        GameMapResponse map = match.getMap();
        int tileSize = map.tileSize();
        int rows = map.seed().size();
        int columns = map.seed().get(0).size();
        double width = columns * tileSize;
        double height = rows * tileSize;
        double allowedDistance;
        if (direction == 0) {
            allowedDistance = Math.min(requestedDistance, tank.getY());
        } else if (direction == 1) {
            allowedDistance = Math.min(requestedDistance, height - tank.getY() - tank.getSize());
        } else if (direction == 2) {
            allowedDistance = Math.min(requestedDistance, tank.getX());
        } else {
            allowedDistance = Math.min(requestedDistance, width - tank.getX() - tank.getSize());
        }
        allowedDistance = Math.max(0.0, allowedDistance);

        double minimumX = tank.getX();
        double minimumY = tank.getY();
        double maximumX = tank.getX() + tank.getSize();
        double maximumY = tank.getY() + tank.getSize();
        if (direction == 0) {
            minimumY = minimumY - allowedDistance;
        } else if (direction == 1) {
            maximumY = maximumY + allowedDistance;
        } else if (direction == 2) {
            minimumX = minimumX - allowedDistance;
        } else {
            maximumX = maximumX + allowedDistance;
        }

        int firstRow = Math.max(0, (int) Math.floor(minimumY / tileSize));
        int lastRow = Math.min(rows - 1, (int) Math.floor((maximumY - EPSILON) / tileSize));
        int firstColumn = Math.max(0, (int) Math.floor(minimumX / tileSize));
        int lastColumn = Math.min(columns - 1, (int) Math.floor((maximumX - EPSILON) / tileSize));
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = firstColumn; column <= lastColumn; column++) {
                int material = map.seed().get(row).get(column);
                int cellIndex = row * columns + column;
                if (isPassable(material) || material == 1 && match.getDestroyedCells().contains(cellIndex)) {
                    continue;
                }
                double obstacleX = column * tileSize;
                double obstacleY = row * tileSize;
                allowedDistance = limitDistanceForObstacle(tank, direction, allowedDistance,
                        obstacleX, obstacleY, tileSize);
            }
        }

        if (match.getBase() != null) {
            allowedDistance = limitDistanceForObstacle(tank, direction, allowedDistance,
                    match.getBase().getX(), match.getBase().getY(), match.getBase().getSize());
        }
        for (TankState otherTank : match.allTanks()) {
            if (otherTank == tank || otherTank.getHp() <= 0) {
                continue;
            }
            allowedDistance = limitDistanceForObstacle(tank, direction, allowedDistance,
                    otherTank.getX(), otherTank.getY(), otherTank.getSize());
        }
        return allowedDistance;
    }

    /** 只检查行进前方且与坦克侧边有重叠的障碍；边缘接触本身不算穿入。 */
    private double limitDistanceForObstacle(TankState tank, int direction, double distance,
                                            double obstacleX, double obstacleY, double obstacleSize) {
        double left = tank.getX();
        double top = tank.getY();
        double right = left + tank.getSize();
        double bottom = top + tank.getSize();
        double obstacleRight = obstacleX + obstacleSize;
        double obstacleBottom = obstacleY + obstacleSize;
        double gap = distance;
        if (direction == 0 && overlaps(left, right, obstacleX, obstacleRight)
                && obstacleBottom <= top + EPSILON) {
            gap = top - obstacleBottom;
        } else if (direction == 1 && overlaps(left, right, obstacleX, obstacleRight)
                && obstacleY >= bottom - EPSILON) {
            gap = obstacleY - bottom;
        } else if (direction == 2 && overlaps(top, bottom, obstacleY, obstacleBottom)
                && obstacleRight <= left + EPSILON) {
            gap = left - obstacleRight;
        } else if (direction == 3 && overlaps(top, bottom, obstacleY, obstacleBottom)
                && obstacleX >= right - EPSILON) {
            gap = obstacleX - right;
        }
        double allowedDistance = Math.min(distance, Math.max(0.0, gap));
        return allowedDistance;
    }

    private boolean overlaps(double firstStart, double firstEnd, double secondStart, double secondEnd) {
        return firstStart < secondEnd - EPSILON && firstEnd > secondStart + EPSILON;
    }

    private TankState createTank(Long userId, double size, GridCell spawn, int tileSize, int direction) {
        TankState tank = new TankState(userId, size);
        double offset = (tileSize - size) / 2.0;
        tank.setX(spawn.getColumn() * tileSize + offset);
        tank.setY(spawn.getRow() * tileSize + offset);
        tank.setDirection(direction);
        return tank;
    }

    private GameMapResponse copyAndValidateMap(GameMapResponse sourceMap) {
        if (sourceMap == null || sourceMap.tileSize() == null
                || sourceMap.tileSize() < 8 || sourceMap.tileSize() > 64) {
            throw invalidMap("地图格子边长必须在8到64之间");
        }
        List<List<Integer>> sourceSeed = sourceMap.seed();
        if (sourceSeed == null || sourceSeed.isEmpty() || sourceSeed.size() > MAX_MAP_LENGTH
                || sourceSeed.get(0) == null || sourceSeed.get(0).isEmpty()
                || sourceSeed.get(0).size() > MAX_MAP_LENGTH) {
            throw invalidMap("地图行列数量必须在1到128之间");
        }

        int columns = sourceSeed.get(0).size();
        List<List<Integer>> seedCopy = new ArrayList<List<Integer>>();
        for (List<Integer> sourceRow : sourceSeed) {
            if (sourceRow == null || sourceRow.size() != columns) {
                throw invalidMap("地图必须是行长度一致的矩形");
            }
            List<Integer> rowCopy = new ArrayList<Integer>();
            for (Integer material : sourceRow) {
                if (material == null || material < 0 || material > 5) {
                    throw invalidMap("地图材质编号必须在0到5之间");
                }
                rowCopy.add(material);
            }
            seedCopy.add(List.copyOf(rowCopy));
        }
        GameMapResponse copy = new GameMapResponse(sourceMap.id(), sourceMap.name(),
                sourceMap.tileSize(), List.copyOf(seedCopy));
        return copy;
    }

    private List<GridCell> findLargestComponent(GameMapResponse map) {
        int rows = map.seed().size();
        int columns = map.seed().get(0).size();
        boolean[][] visited = new boolean[rows][columns];
        List<GridCell> largest = new ArrayList<GridCell>();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (visited[row][column] || !isPassable(map.seed().get(row).get(column))) {
                    continue;
                }
                List<GridCell> component = new ArrayList<GridCell>();
                Queue<GridCell> queue = new ArrayDeque<GridCell>();
                GridCell first = new GridCell(row, column);
                queue.add(first);
                visited[row][column] = true;
                while (!queue.isEmpty()) {
                    GridCell current = queue.remove();
                    component.add(current);
                    for (int index = 0; index < ROW_CHANGES.length; index++) {
                        int nextRow = current.getRow() + ROW_CHANGES[index];
                        int nextColumn = current.getColumn() + COLUMN_CHANGES[index];
                        if (isInside(nextRow, nextColumn, rows, columns)
                                && !visited[nextRow][nextColumn]
                                && isPassable(map.seed().get(nextRow).get(nextColumn))) {
                            visited[nextRow][nextColumn] = true;
                            GridCell next = new GridCell(nextRow, nextColumn);
                            queue.add(next);
                        }
                    }
                }
                if (component.size() > largest.size()) {
                    largest = component;
                }
            }
        }
        return largest;
    }

    private GridCell findFarthestCell(GameMapResponse map, GridCell first) {
        int rows = map.seed().size();
        int columns = map.seed().get(0).size();
        int[][] distances = new int[rows][columns];
        for (int[] row : distances) {
            Arrays.fill(row, -1);
        }
        distances[first.getRow()][first.getColumn()] = 0;
        Queue<GridCell> queue = new ArrayDeque<GridCell>();
        queue.add(first);
        GridCell farthest = first;
        int farthestDistance = 0;
        while (!queue.isEmpty()) {
            GridCell current = queue.remove();
            int currentDistance = distances[current.getRow()][current.getColumn()];
            if (currentDistance > farthestDistance || (currentDistance == farthestDistance
                    && cellOrder(current, columns) > cellOrder(farthest, columns))) {
                farthest = current;
                farthestDistance = currentDistance;
            }
            for (int index = 0; index < ROW_CHANGES.length; index++) {
                int nextRow = current.getRow() + ROW_CHANGES[index];
                int nextColumn = current.getColumn() + COLUMN_CHANGES[index];
                if (isInside(nextRow, nextColumn, rows, columns) && distances[nextRow][nextColumn] < 0
                        && isPassable(map.seed().get(nextRow).get(nextColumn))) {
                    distances[nextRow][nextColumn] = currentDistance + 1;
                    GridCell next = new GridCell(nextRow, nextColumn);
                    queue.add(next);
                }
            }
        }
        return farthest;
    }

    private boolean isInside(int row, int column, int rows, int columns) {
        return row >= 0 && row < rows && column >= 0 && column < columns;
    }

    private boolean isPassable(int material) {
        return material == 0 || material == 4 || material == 5;
    }

    private int cellOrder(GridCell cell, int columns) {
        return cell.getRow() * columns + cell.getColumn();
    }

    private BusinessException invalidMap(String message) {
        BusinessException exception = new BusinessException(HttpStatus.BAD_REQUEST, message);
        return exception;
    }

    /** 搜索用坐标，Lombok 构造参数为 row、column。 */
    @Getter
    @RequiredArgsConstructor
    private static class GridCell {
        private final int row;
        private final int column;
    }
}
