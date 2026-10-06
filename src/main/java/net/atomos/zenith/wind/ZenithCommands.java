package net.atomos.zenith.wind;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.atomos.zenith.api.GameplayWindSample;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.ZenithWorldRef;
import net.atomos.zenith.api.ZenithId;
import net.atomos.zenith.api.minecraft.ZenithMinecraftWindApi;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 服务端指令：/zenith status|wind|storm|typhoon|thermal|seabreeze。
 */
public final class ZenithCommands {
    private ZenithCommands() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ZenithCommands::register);
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();

        d.register(Commands.literal("zenith")
                .then(Commands.literal("status").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerLevel level = src.getLevel();
                    var runtime = ZenithServerRuntime.get();
                    var state = runtime.debugState(level.dimension());
                    if (state == null) {
                        src.sendFailure(Component.literal("该维度风场尚未初始化"));
                        return 0;
                    }
                    src.sendSuccess(() -> Component.literal(String.format(
                            "Zenith 状态 [%s]\n风暴活动度: %.2f  龙卷: %d\n热泡: %d  风暴单体: %d  台风: %d  海风锋: %.0fm",
                            level.dimension().location(),
                            state.driver.stormActivity(), state.driver.activeTornadoCount(),
                            state.thermals.activeCount(), state.storms.activeCount(),
                            state.typhoons.activeCount(), state.seaBreeze.frontInlandDistance())),
                            false);
                    return 1;
                }))
                .then(Commands.literal("wind").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer player = src.getPlayerOrException();
                    GameplayWindSample wind = ZenithMinecraftWindApi.sampleGameplay(
                            player.serverLevel(), player.getEyePosition());
                    if (!wind.isTrustedForGameplay()) {
                        src.sendFailure(Component.literal("风场数据不可用"));
                        return 0;
                    }
                    ZenithVec3 v = wind.effectiveVelocityVector();
                    src.sendSuccess(() -> Component.literal(String.format(
                            "风速: %.1f m/s (%.1f, %.1f, %.1f)  湍流: %.0f%%  遮蔽: %s",
                            v.length(), v.x(), v.y(), v.z(),
                            wind.turbulenceIntensity() * 100,
                            wind.isSheltered() ? "是" : "否")), false);
                    return 1;
                }))
                .then(Commands.literal("storm").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer player = src.getPlayerOrException();
                    var state = ZenithServerRuntime.get().debugState(player.serverLevel().dimension());
                    if (state == null) return 0;
                    BlockPos p = player.blockPosition();
                    state.storms.spawnDebug(p.getX(), p.getZ());
                    src.sendSuccess(() -> Component.literal("已在脚下生成风暴单体（成熟期）"), true);
                    return 1;
                }))
                .then(Commands.literal("typhoon").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer player = src.getPlayerOrException();
                    var state = ZenithServerRuntime.get().debugState(player.serverLevel().dimension());
                    if (state == null) return 0;
                    BlockPos p = player.blockPosition();
                    state.typhoons.spawnDebug(p.getX() + 300, p.getZ());
                    src.sendSuccess(() -> Component.literal("已在东侧 300m 生成台风"), true);
                    return 1;
                }))
                .then(Commands.literal("thermal").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer player = src.getPlayerOrException();
                    var state = ZenithServerRuntime.get().debugState(player.serverLevel().dimension());
                    if (state == null) return 0;
                    BlockPos p = player.blockPosition();
                    state.thermals.spawnDebug(p.getX() + 20, p.getZ(),
                            player.serverLevel().getSeaLevel());
                    src.sendSuccess(() -> Component.literal("已生成测试热泡"), true);
                    return 1;
                }))
                .then(Commands.literal("seabreeze").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer player = src.getPlayerOrException();
                    var state = ZenithServerRuntime.get().debugState(player.serverLevel().dimension());
                    if (state == null) return 0;
                    Vec3 eye = player.getEyePosition();
                    var c = state.seaBreeze.sample(eye.x, eye.y, eye.z, 0, 0);
                    src.sendSuccess(() -> Component.literal(String.format(
                            "海风贡献: (%.1f, %.1f, %.1f) m/s  海风锋离岸: %.0fm",
                            c.vx(), c.vy(), c.vz(), state.seaBreeze.frontInlandDistance())), false);
                    return 1;
                }))
                .then(Commands.literal("polar")
                        .then(Commands.argument("chord", DoubleArgumentType.doubleArg(0.1, 100))
                                .then(Commands.argument("span", DoubleArgumentType.doubleArg(0.1, 100))
                                        .executes(ctx -> {
                                            CommandSourceStack src = ctx.getSource();
                                            double chord = DoubleArgumentType.getDouble(ctx, "chord");
                                            double span = DoubleArgumentType.getDouble(ctx, "span");
                                            var req = net.atomos.zenith.api.ZenithPolarRequest.builder(
                                                            new net.atomos.zenith.api.ZenithSurfaceDescriptor(
                                                                    ZenithId.of("zenith", "naca_2412"),
                                                                    chord, span, 0))
                                                    .angleSweep(-15, 15, 3).build();
                                            var res = net.atomos.zenith.api.ZenithWindApi.runPolar(req);
                                            if (!res.succeeded()) {
                                                src.sendFailure(Component.literal("极线计算失败: " + res.message()));
                                                return 0;
                                            }
                                            StringBuilder sb = new StringBuilder("α → Cl / Cd:\n");
                                            for (var s : res.table().samples()) {
                                                sb.append(String.format("  %+.0f°: %.3f / %.4f\n",
                                                        s.angleDegrees(), s.cl(), s.cd()));
                                            }
                                            src.sendSuccess(() -> Component.literal(sb.toString()), false);
                                            return 1;
                                        })))));
    }
}
