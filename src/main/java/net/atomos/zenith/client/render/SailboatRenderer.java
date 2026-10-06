package net.atomos.zenith.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.atomos.zenith.entity.SailboatEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 帆船渲染器：船体 + 桅杆 + 三角帆（纯色几何，无贴图）。 */
@OnlyIn(Dist.CLIENT)
public class SailboatRenderer extends EntityRenderer<SailboatEntity> {
    public SailboatRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    @Override
    public void render(SailboatEntity boat, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light) {
        pose.pushPose();
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180 - yaw));
        VertexConsumer vc = buffers.getBuffer(ClientRenderHelper.FLAT_COLOR);

        // 船体
        ClientRenderHelper.renderBox(pose, vc,
                -0.55f, 0f, -0.9f, 0.55f, 0.45f, 0.9f,
                0.42f, 0.28f, 0.16f, 1f);
        // 船舷
        ClientRenderHelper.renderBox(pose, vc,
                -0.62f, 0.35f, -0.95f, 0.62f, 0.6f, 0.95f,
                0.35f, 0.22f, 0.12f, 1f);
        // 桅杆
        ClientRenderHelper.renderBox(pose, vc,
                -0.05f, 0.45f, -0.05f, 0.05f, 2.6f, 0.05f,
                0.3f, 0.2f, 0.12f, 1f);
        // 帆（三角，帆 trim 影响张角示意）
        float trim = boat.getSailTrim();
        float belly = 0.12f + trim * 0.25f;
        ClientRenderHelper.renderTriangle(pose, vc,
                0.02f, 2.55f, 0f,          // 帆顶
                0.02f, 0.6f, 0.1f,         // 帆脚前
                0.02f + belly, 1.5f, -1.15f, // 帆脚后（鼓出）
                0.92f, 0.88f, 0.82f, 1f);

        pose.popPose();
        super.render(boat, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(SailboatEntity entity) {
        return ResourceLocation.withDefaultNamespace("textures/entity/boat/oak.png");
    }
}
