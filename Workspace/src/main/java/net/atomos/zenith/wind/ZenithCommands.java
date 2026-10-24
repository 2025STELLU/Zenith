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
 * 服务端指令：/zenith status|wind|storm|typhoon|thermal|seabreeze|polar。
 * 会实际改写世界天气的子命令（storm/typhoon/thermal）要 OP 2 级；
 * 纯查询的不拦。
 */
public final class ZenithCommands {
    private ZenithCommands() {}

    // MC 的 TranslatableContents 只认 %s，不认 %d / %.2f：数字先在服务端格式化成字符串再传进去
    private static String f(double v, int decimals) {
        return String.format(java.util.Locale.ROOT, "%." + decimals + "f", v);
    }

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
                        src.sendFailure(Component.translatable("zenith.command.not_initialized"));
                        return 0;
                    }
                    src.sendSuccess(() -> Component.translatable("zenith.command.status",
                            level.dimension().location().toString(),
                            f(state.driver.stormActivity(), 2),
                            String.valueOf(state.driver.activeTornadoCount()),
                            String.valueOf(state.thermals.activeCount()),
                            String.valueOf(state.storms.activeCount()),
                            String.valueOf(state.typhoons.activeCount()),
                            f(state.seaBreeze.frontInlandDistance(), 0)),
                            false);
                    return 1;
                }))
                .then(Commands.literal("wind").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer player = src.getPlayerOrException();
                    GameplayWindSample wind = ZenithMinecraftWindApi.sampleGameplay(
                            player.serverLevel(), player.getEyePosition());
                    if (!wind.isTrustedForGameplay()) {
                        src.sendFailure(Component.translatable("zenith.command.wind_unavailable"));
                        return 0;
                    }
                    ZenithVec3 v = wind.effectiveVelocityVector();
                    src.sendSuccess(() -> Component.translatable("zenith.command.wind",
                            f(v.length(), 1), f(v.x(), 1), f(v.y(), 1), f(v.z(), 1),
                            f(wind.turbulenceIntensity() * 100, 0) + "%",
                            Component.translatable(wind.isSheltered()
                                    ? "zenith.command.yes" : "zenith.command.no")), false);
                    return 1;
                }))
                .then(Commands.literal("storm").requires(src -> src.hasPermission(2)).executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer player = src.getPlayerOrException();
                    var state = ZenithServerRuntime.get().debugState(player.serverLevel().dimension());
                    if (state == null) return 0;
                    BlockPos p = player.blockPosition();
                    state.storms.spawnDebug(p.getX(), p.getZ());
                    src.sendSuccess(() -> Component.translatable("zenith.command.storm_spawned"), true);
                    return 1;
                }))
                .then(Commands.literal("typhoon").requires(src -> src.hasPermission(2)).executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer player = src.getPlayerOrException();
                    var state = ZenithServerRuntime.get().debugState(player.serverLevel().dimension());
                    if (state == null) return 0;
                    BlockPos p = player.blockPosition();
                    state.typhoons.spawnDebug(p.getX() + 300, p.getZ());
                    src.sendSuccess(() -> Component.translatable("zenith.command.typhoon_spawned"), true);
                    return 1;
                }))
                .then(Commands.literal("thermal").requires(src -> src.hasPermission(2)).executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer player = src.getPlayerOrException();
                    var state = ZenithServerRuntime.get().debugState(player.serverLevel().dimension());
                    if (state == null) return 0;
                    BlockPos p = player.blockPosition();
                    state.thermals.spawnDebug(p.getX() + 20, p.getZ(),
                            player.serverLevel().getSeaLevel());
                    src.sendSuccess(() -> Component.translatable("zenith.command.thermal_spawned"), true);
                    return 1;
                }))
                .then(Commands.literal("seabreeze").executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    ServerPlayer player = src.getPlayerOrException();
                    var state = ZenithServerRuntime.get().debugState(player.serverLevel().dimension());
                    if (state == null) return 0;
                    Vec3 eye = player.getEyePosition();
                    var c = state.seaBreeze.sample(eye.x, eye.y, eye.z, 0, 0);
                    src.sendSuccess(() -> Component.translatable("zenith.command.seabreeze",
                            f(c.vx(), 1), f(c.vy(), 1), f(c.vz(), 1),
                            f(state.seaBreeze.frontInlandDistance(), 0)), false);
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
                                                src.sendFailure(Component.translatable(
                                                        "zenith.command.polar_failed", res.message()));
                                                return 0;
                                            }
                                            // 逐行 translatable：让客户端按自己的语言渲染，别在服务端 getString() 写死英文
                                            var out = Component.translatable("zenith.command.polar_header");
                                            for (var s : res.table().samples()) {
                                                out.append(Component.literal("\n")).append(
                                                        Component.translatable("zenith.command.polar_row",
                                                                String.format(java.util.Locale.ROOT,
                                                                        "%+.0f", s.angleDegrees()),
                                                                f(s.cl(), 3), f(s.cd(), 4)));
                                            }
                                            src.sendSuccess(() -> out, false);
                                            return 1;
                                        })))));
    }
}
