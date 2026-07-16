package org.kseco.extra.realestatedungeon;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.block.BlockTypes;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * FAWE / WorldEdit 隔离层 —— 整个副本模块**唯一**直接引用 WorldEdit API 的类。
 *
 * <p>设计意图：本模块以 JAR 形式被 ks-Eco 反射加载。若服务器未装 FastAsyncWorldEdit，
 * 任何含 {@code com.sk89q.worldedit.*} 引用的类在被类加载器解析时会抛 {@link NoClassDefFoundError}。
 * 把所有 WorldEdit 引用收口到这一个类，调用方在触达前用 {@link #isAvailable()} 守卫并 try/catch 包裹，
 * 即可保证「缺 FAWE 时副本系统其余功能（购票/网格/复活）照常工作，仅没有地图」。</p>
 *
 * <p>schematic 解析顺序（便于授图）：
 * <ol>
 *   <li>{@code plugins/ks-Eco/dungeon_schematics/<name>.schem}（本模块自有目录，推荐，解耦）</li>
 *   <li>{@code plugins/ks-Eco/dungeon_schematics/<name>}（已带扩展名时）</li>
 *   <li>{@code plugins/FastAsyncWorldEdit/schematics/<name>.schem}（管理员 //schem save 后可直接引用名）</li>
 *   <li>{@code plugins/WorldEdit/schematics/<name>.schem}</li>
 * </ol></p>
 */
public final class SchematicService {

    private SchematicService() {}

    /** FAWE/WorldEdit 是否在 classpath（运行期是否已装）。守卫所有调用。 */
    public static boolean isAvailable() {
        try {
            Class.forName("com.sk89q.worldedit.WorldEdit");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 解析 schematic 文件，按上文顺序查找。
     * @param dataFolder ks-Eco 的 dataFolder（plugins/ks-Eco）
     * @param name 图名（可带或不带 .schem 扩展名）
     * @return 存在的文件，或 null
     */
    public static File resolveFile(File dataFolder, String name) {
        if (name == null || name.isBlank()) return null;
        String base = dataFolder.getParentFile() == null ? "plugins" : dataFolder.getParent();
        File own = new File(dataFolder, "dungeon_schematics");
        File[] candidates = {
                new File(own, name.endsWith(".schem") ? name : name + ".schem"),
                new File(own, name),
                new File(base, "FastAsyncWorldEdit/schematics/" + (name.endsWith(".schem") ? name : name + ".schem")),
                new File(base, "WorldEdit/schematics/" + (name.endsWith(".schem") ? name : name + ".schem")),
        };
        for (File f : candidates) {
            if (f.isFile()) return f;
        }
        return null;
    }

    /**
     * 把 schematic 贴到 (x,y,z)（clipboard 原点对齐到该点）。可在异步线程调用（FAWE 支持异步 EditSession）。
     *
     * @return 贴图占据的世界绝对包围盒 {@code int[]{minX,minY,minZ,maxX,maxY,maxZ}}（含端点），失败返回 null。
     */
    public static int[] paste(Plugin plugin, World bukkitWorld, File file, int x, int y, int z) {
        if (bukkitWorld == null || file == null || !file.isFile()) return null;
        try {
            ClipboardFormat fmt = ClipboardFormats.findByFile(file);
            if (fmt == null) {
                plugin.getLogger().warning("[副本系统] 无法识别 schematic 格式: " + file.getName());
                return null;
            }
            Clipboard clipboard;
            try (InputStream in = new FileInputStream(file);
                 ClipboardReader reader = fmt.getReader(in)) {
                clipboard = reader.read();
            }

            BlockVector3 to = BlockVector3.at(x, y, z);
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
            try (EditSession edit = WorldEdit.getInstance().newEditSession(weWorld)) {
                Operation op = new ClipboardHolder(clipboard)
                        .createPaste(edit)
                        .to(to)
                        .ignoreAirBlocks(false)
                        .build();
                Operations.complete(op);
            }

            // 计算世界绝对包围盒：世界坐标 = to + (clip坐标 - clip原点)
            Region region = clipboard.getRegion();
            BlockVector3 clipOrigin = clipboard.getOrigin();
            BlockVector3 wMin = to.add(region.getMinimumPoint().subtract(clipOrigin));
            BlockVector3 wMax = to.add(region.getMaximumPoint().subtract(clipOrigin));
            return new int[]{
                    Math.min(wMin.getX(), wMax.getX()), Math.min(wMin.getY(), wMax.getY()), Math.min(wMin.getZ(), wMax.getZ()),
                    Math.max(wMin.getX(), wMax.getX()), Math.max(wMin.getY(), wMax.getY()), Math.max(wMin.getZ(), wMax.getZ())
            };
        } catch (Throwable t) {
            plugin.getLogger().warning("[副本系统] 贴图失败 (" + file.getName() + "): " + t.getMessage());
            return null;
        }
    }

    /**
     * 把一个立方体区域全部设为空气（清场 / 贴图前清画布，回收区块内存）。可异步调用。
     */
    public static boolean clearRegion(Plugin plugin, World bukkitWorld, int x1, int y1, int z1,
                                      int x2, int y2, int z2) {
        if (bukkitWorld == null) return false;
        try {
            com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(bukkitWorld);
            BlockVector3 min = BlockVector3.at(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2));
            BlockVector3 max = BlockVector3.at(Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
            try (EditSession edit = WorldEdit.getInstance().newEditSession(weWorld)) {
                CuboidRegion region = new CuboidRegion(weWorld, min, max);
                edit.setBlocks((Region) region, BlockTypes.AIR.getDefaultState());
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[副本系统] 清场失败: " + t.getMessage());
            return false;
        }
    }
}
