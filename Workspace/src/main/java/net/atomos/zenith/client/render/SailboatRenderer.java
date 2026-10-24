package net.atomos.zenith.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.atomos.zenith.entity.SailboatEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
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
        // SAIL-02：几何按"船头朝 +Z（南）"建模，而 EntityRenderer 传进来的 yaw 就是实体的
        // MC yaw（0=+Z、顺时针正）。把模型里的 +Z 转到 (-sin yaw, cos yaw) 只需绕 Y 转 -yaw。
        // 旧代码用 180-yaw（那是给"模型朝 -Z"的 LivingEntity 用的），效果是模型朝反方向，
        // 只好再靠 setYRot 的反号凑出"视觉正好"。
        pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-yaw));
        // 受击晃动：照原版 BoatRenderer，绕船体 X 轴滚一下，幅度随受伤时间衰减
        float hurt = boat.getHurtTime() - partialTick;
        float dmg = Math.max(boat.getDamage() - partialTick, 0f);
        if (hurt > 0) {
            pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(
                    Mth.sin(hurt) * hurt * dmg / 10f * boat.getHurtDir()));
        }
        VertexConsumer vc = buffers.getBuffer(ClientRenderHelper.FLAT_COLOR);
        // CLI-09：直接用渲染管线给的打包光照（EntityRenderer 已经把船所在位置的光照算好）
        int packedLight = light;

        // 船体
        ClientRenderHelper.renderBox(pose, vc,
                -0.55f, 0f, -0.9f, 0.55f, 0.45f, 0.9f,
                0.42f, 0.28f, 0.16f, 1f, packedLight);
        // 船舷
        ClientRenderHelper.renderBox(pose, vc,
                -0.62f, 0.35f, -0.95f, 0.62f, 0.6f, 0.95f,
                0.35f, 0.22f, 0.12f, 1f, packedLight);
        // 桅杆
        ClientRenderHelper.renderBox(pose, vc,
                -0.05f, 0.45f, -0.05f, 0.05f, 2.6f, 0.05f,
                0.3f, 0.2f, 0.12f, 1f, packedLight);
        // 帆（三角，帆 trim 影响张角示意）
        float trim = boat.getSailTrim();
        float belly = 0.12f + trim * 0.25f;
        ClientRenderHelper.renderTriangle(pose, vc,
                0.02f, 2.55f, 0f,          // 帆顶
                0.02f, 0.6f, 0.1f,         // 帆脚前
                0.02f + belly, 1.5f, -1.15f, // 帆脚后（鼓出）
                0.92f, 0.88f, 0.82f, 1f, packedLight);

        pose.popPose();
        super.render(boat, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(SailboatEntity entity) {
        return ResourceLocation.withDefaultNamespace("textures/entity/boat/oak.png");
    }
}
