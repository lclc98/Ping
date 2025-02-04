package dmillerw.ping.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import dmillerw.ping.Ping;
import dmillerw.ping.client.util.GLUUtils;
import dmillerw.ping.client.util.PingRenderHelper;
import dmillerw.ping.client.util.VertexHelper;
import dmillerw.ping.data.PingType;
import dmillerw.ping.data.PingWrapper;
import dmillerw.ping.network.packet.ServerBroadcastPing;
import dmillerw.ping.util.Config;
import dmillerw.ping.util.PingSounds;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@EventBusSubscriber(modid = Ping.MOD_ID, value = Dist.CLIENT)
public class PingHandler {
    public static final PingHandler INSTANCE = new PingHandler();
    public static final ResourceLocation TEXTURE = new ResourceLocation(Ping.MOD_ID, "textures/ping.png");
    private static final List<PingWrapper> ACTIVE_PINGS = new ArrayList<>();

    public void onPingPacket(ServerBroadcastPing packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && Mth.sqrt((float) mc.player.distanceToSqr(packet.ping.pos.getX(), packet.ping.pos.getY(), packet.ping.pos.getZ())) <= Config.GENERAL.pingAcceptDistance.get()) {
            if (Config.GENERAL.sound.get()) {
                mc.getSoundManager().play(new SimpleSoundInstance(PingSounds.BLOOP, SoundSource.PLAYERS, 0.25F, 1.0F, packet.ping.pos.getX(), packet.ping.pos.getY(), packet.ping.pos.getZ()));
            }
            packet.ping.timer = Config.GENERAL.pingDuration.get();
            ACTIVE_PINGS.add(packet.ping);
        }
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderWorldLastEvent event) {
        if (ACTIVE_PINGS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.getBlockEntityRenderDispatcher().camera;
        Vec3 cameraPos = camera.getPosition();

        Frustum clippingHelper = new Frustum(event.getMatrixStack().last().pose(), event.getProjectionMatrix());
        clippingHelper.prepare(cameraPos.x(), cameraPos.y(), cameraPos.z());

        for (PingWrapper ping : ACTIVE_PINGS) {
            double px = ping.pos.getX() + 0.5D - cameraPos.x();
            double py = ping.pos.getY() + 0.5D - cameraPos.y();
            double pz = ping.pos.getZ() + 0.5D - cameraPos.z();

            if (clippingHelper.isVisible(ping.getAABB())) {
                ping.isOffscreen = false;
                if (Config.VISUAL.blockOverlay.get()) {
                    System.out.println("Block overlay");
                    renderPingOverlay(ping.pos.getX() - cameraPos.x(), ping.pos.getY() - cameraPos.y(), ping.pos.getZ() - cameraPos.z(), event.getMatrixStack(), ping);
                }
                System.out.println("Icon");
                renderPing(px, py, pz, event.getMatrixStack(), camera, ping);
            } else {
                ping.isOffscreen = true;
                System.out.println("Offscren");
                translatePingCoordinates(px, py, pz, ping);
            }
        }
    }

    @SubscribeEvent
    public static void renderPingOffscreen(RenderGameOverlayEvent.Post event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.TEXT) {
            Minecraft mc = Minecraft.getInstance();
            for (PingWrapper ping : ACTIVE_PINGS) {
                if (!ping.isOffscreen || mc.screen != null || mc.options.renderDebug) {
                    continue;
                }
                int width = mc.getWindow().getScreenWidth();
                int height = mc.getWindow().getScreenHeight();

                int x1 = -(width / 2) + 32;
                int y1 = -(height / 2) + 32;
                int x2 = (width / 2) - 32;
                int y2 = (height / 2) - 32;

                double pingX = ping.screenX;
                double pingY = ping.screenY;

                pingX -= width * 0.5D;
                pingY -= height * 0.5D;

                //TODO Fix that player rotation is not being taken into account. Been an issue since the creation of the mod
                double angle = Math.atan2(pingY, pingX);
                angle += (Math.toRadians(90));
                double cos = Math.cos(angle);
                double sin = Math.sin(angle);
                double m = cos / sin;

                if (cos > 0) {
                    pingX = y2 / m;
                    pingY = y2;
                } else {
                    pingX = y1 / m;
                    pingY = y1;
                }

                if (pingX > x2) {
                    pingX = x2;
                    pingY = x2 * m;
                } else if (pingX < x1) {
                    pingX = x1;
                    pingY = x1 * m;
                }

                pingX += width * 0.5D;
                pingY += height * 0.5D;

                PoseStack poseStack = new PoseStack();
                poseStack.pushPose();
                PoseStack.Pose matrixEntry = poseStack.last();
                poseStack.translate(pingX / 2, pingY / 2, 0);
                RenderSystem.applyModelViewMatrix();

                Tesselator tesselator = Tesselator.getInstance();
                BufferBuilder vertexBuilder = tesselator.getBuilder();
                RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
                RenderSystem.setShaderTexture(0, TEXTURE);
                final Matrix4f matrix4f = matrixEntry.pose();

                vertexBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                float min = -8;
                float max = 8;

                // Ping Notice Background
                int r = ping.color >> 16 & 255;
                int g = ping.color >> 8 & 255;
                int b = ping.color & 255;
                VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, min, max, PingType.BACKGROUND.getMinU(), PingType.BACKGROUND.getMaxV(), r, g, b, 255);
                VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, max, max, PingType.BACKGROUND.getMaxU(), PingType.BACKGROUND.getMaxV(), r, g, b, 255);
                VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, max, min, PingType.BACKGROUND.getMaxU(), PingType.BACKGROUND.getMinV(), r, g, b, 255);
                VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, min, min, PingType.BACKGROUND.getMinU(), PingType.BACKGROUND.getMinV(), r, g, b, 255);

                // Ping Notice Icon
                float alpha = ping.type == PingType.ALERT ? mc.level != null ? (float) (1.0F + (0.01D * Math.sin(mc.level.getDayTime()))) : 0.85F : 0.85F;
                VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, min, max, ping.type.getMinU(), ping.type.getMaxV(), 1.0F, 1.0F, 1.0F, alpha);
                VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, max, max, ping.type.getMaxU(), ping.type.getMaxV(), 1.0F, 1.0F, 1.0F, alpha);
                VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, max, min, ping.type.getMaxU(), ping.type.getMinV(), 1.0F, 1.0F, 1.0F, alpha);
                VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, min, min, ping.type.getMinU(), ping.type.getMinV(), 1.0F, 1.0F, 1.0F, alpha);
                tesselator.end();

                poseStack.popPose();
                RenderSystem.applyModelViewMatrix();
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Iterator<PingWrapper> iterator = ACTIVE_PINGS.iterator();
        while (iterator.hasNext()) {
            PingWrapper pingWrapper = iterator.next();
            if (pingWrapper.animationTimer > 0) {
                pingWrapper.animationTimer -= 5;
            }
            pingWrapper.timer--;

            if (pingWrapper.timer <= 0) {
                iterator.remove();
            }
        }
    }

    private static void renderPing(double px, double py, double pz, PoseStack poseStack, Camera camera, PingWrapper ping) {
        Minecraft mc = Minecraft.getInstance();
        poseStack.pushPose();
        poseStack.translate(px, py, pz);
        poseStack.mulPose(Vector3f.YP.rotationDegrees(-camera.getYRot()));
        poseStack.mulPose(Vector3f.XP.rotationDegrees(camera.getXRot()));
        poseStack.mulPose(Vector3f.ZP.rotationDegrees(180.0F));

        PoseStack.Pose matrixEntry = poseStack.last();
        Matrix4f matrix4f = matrixEntry.pose();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        RenderType pingType = PingRenderType.getPingIcon(TEXTURE);
        VertexConsumer vertexBuilder = buffer.getBuffer(pingType);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);

        float min = -0.25F - (0.25F * (float) ping.animationTimer / 20F);
        float max = 0.25F + (0.25F * (float) ping.animationTimer / 20F);

        // Block Overlay Background
        int r = ping.color >> 16 & 255;
        int g = ping.color >> 8 & 255;
        int b = ping.color & 255;
        VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, min, max, PingType.BACKGROUND.getMinU(), PingType.BACKGROUND.getMaxV(), r, g, b, 255);
        VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, max, max, PingType.BACKGROUND.getMaxU(), PingType.BACKGROUND.getMaxV(), r, g, b, 255);
        VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, max, min, PingType.BACKGROUND.getMaxU(), PingType.BACKGROUND.getMinV(), r, g, b, 255);
        VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, min, min, PingType.BACKGROUND.getMinU(), PingType.BACKGROUND.getMinV(), r, g, b, 255);

        // Block Overlay Icon
        float alpha = ping.type == PingType.ALERT ? mc.level != null ? (float) (1.0F + (0.01D * Math.sin(mc.level.getDayTime()))) : 0.85F : 0.85F;
        VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, min, max, ping.type.getMinU(), ping.type.getMaxV(), 1.0F, 1.0F, 1.0F, alpha);
        VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, max, max, ping.type.getMaxU(), ping.type.getMaxV(), 1.0F, 1.0F, 1.0F, alpha);
        VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, max, min, ping.type.getMaxU(), ping.type.getMinV(), 1.0F, 1.0F, 1.0F, alpha);
        VertexHelper.renderPosTexColorNoZ(vertexBuilder, matrix4f, min, min, ping.type.getMinU(), ping.type.getMinV(), 1.0F, 1.0F, 1.0F, alpha);
        buffer.endBatch(pingType);

        poseStack.popPose();
    }

    private static void renderPingOverlay(double x, double y, double z, PoseStack poseStack, PingWrapper ping) {
        TextureAtlasSprite icon = Minecraft.getInstance().getItemRenderer().getItemModelShaper().getItemModel(new ItemStack(Blocks.WHITE_STAINED_GLASS)).getParticleIcon();
        float padding = 0F + (0.20F * (float) ping.animationTimer / (float) 20);
        float box = 1 + padding + padding;

        poseStack.pushPose();
        poseStack.translate(x + 0.5, y + 0.5, z + 0.5);
        PingRenderHelper.drawBlockOverlay(box, box, box, poseStack, icon, ping.color, 175);
        poseStack.translate(0, 0, 0);
        poseStack.popPose();
    }

    private static void translatePingCoordinates(double px, double py, double pz, PingWrapper ping) {
        FloatBuffer screenCoords = BufferUtils.createFloatBuffer(4);
        IntBuffer viewport = BufferUtils.createIntBuffer(16);
        FloatBuffer modelView = BufferUtils.createFloatBuffer(16);
        FloatBuffer projection = BufferUtils.createFloatBuffer(16);

        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, modelView);
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);

        if (GLUUtils.gluProject((float) px, (float) py, (float) pz, modelView, projection, viewport, screenCoords)) {
            ping.screenX = screenCoords.get(0);
            ping.screenY = screenCoords.get(1);
        }
    }
}