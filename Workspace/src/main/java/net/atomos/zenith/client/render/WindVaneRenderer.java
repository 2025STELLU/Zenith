package net.atomos.zenith.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.atomos.zenith.block.WindVaneBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 风向标渲染器：杆 + 随风向旋转的箭头。 */
@OnlyIn(Dist.CLIENT)
public class WindVaneRenderer implements BlockEntityRenderer<WindVaneBlockEntity> {
    public WindVaneRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(WindVaneBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);

        VertexConsumer vc = buffers.getBuffer(ClientRenderHelper.FLAT_COLOR);
        // CLI-09：直接用渲染管线给的打包光照（getLightColor 已经算好，别自己再查一次）
        int packedLight = light;

        // 杆
        ClientRenderHelper.renderBox(pose, vc,
                -0.03f, 0f, -0.03f, 0.03f, 1.0f, 0.03f,
                0.35f, 0.35f, 0.38f, 1f, packedLight);

        // 箭头（绕 Y 旋转）
        pose.pushPose();
        pose.translate(0, 0.92f, 0);
        // 箭头模型朝 +Z；yaw=atan2(x,z)，0=南(+Z)、90=东(+X)。
        // Axis.YP 正旋转把 +Z 转向 +X，所以直接用 yaw（原来取了负号，东西镜像）。
        // 方块实体每 tick 更新 yaw，这里用 prev/cur 做帧间插值，不然每 tick 还是会抖。
        // 注意：箭头指向风的去向（气象惯例是来向，这里保持去向语义不动）。
        float yaw = Mth.rotLerp(partialTick, be.getPrevYawDegrees(), be.getArrowYawDegrees());
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yaw));
        // 箭杆
        ClientRenderHelper.renderBox(pose, vc,
                -0.02f, -0.02f, -0.35f, 0.02f, 0.02f, 0.35f,
                0.85f, 0.2f, 0.2f, 1f, packedLight);
        // 箭头（+Z 为箭头方向）
        ClientRenderHelper.renderTriangle(pose, vc,
                0, -0.02f, 0.35f,
                -0.09f, -0.02f, 0.18f,
                0.09f, -0.02f, 0.18f,
                0.9f, 0.25f, 0.25f, 1f, packedLight);
        ClientRenderHelper.renderTriangle(pose, vc,
                0, 0.02f, 0.35f,
                0.09f, 0.02f, 0.18f,
                -0.09f, 0.02f, 0.18f,
                0.9f, 0.25f, 0.25f, 1f, packedLight);
        // 尾翼
        ClientRenderHelper.renderBox(pose, vc,
                -0.015f, -0.09f, -0.35f, 0.015f, 0.09f, -0.22f,
                0.85f, 0.2f, 0.2f, 1f, packedLight);
        pose.popPose();

        pose.popPose();
    }
}
