package dmillerw.ping.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import dmillerw.ping.Ping;
import dmillerw.ping.client.util.GLUUtils;
import dmillerw.ping.client.util.PingRenderHelper;
import dmillerw.ping.client.util.VertexHelper;
import dmillerw.ping.data.PingType;
import dmillerw.ping.data.PingWrapper;
import dmillerw.ping.network.packet.ServerBroadcastPing;
import dmillerw.ping.util.Config;
import dmillerw.ping.util.PingSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.culling.ClippingHelperImpl;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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
    private static final RenderType PING_RENDER = RenderType.func_228638_b_(TEXTURE);
    private static List<PingWrapper> active_pings = new ArrayList<>();
    private static MatrixStack matrixStack;

    public void onPingPacket(ServerBroadcastPing packet) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && MathHelper.sqrt(mc.player.getDistanceSq(packet.ping.pos.getX(), packet.ping.pos.getY(), packet.ping.pos.getZ())) <= Config.GENERAL.pingAcceptDistance.get()) {
            if (Config.GENERAL.sound.get()) {
                mc.getSoundHandler().play(new SimpleSound(PingSounds.BLOOP, SoundCategory.PLAYERS, 0.25F, 1.0F, packet.ping.pos.getX(), packet.ping.pos.getY(), packet.ping.pos.getZ()));
            }
            packet.ping.timer = Config.GENERAL.pingDuration.get();
            active_pings.add(packet.ping);
        }
    }

    @SubscribeEvent
    public static void onRenderWorld(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Entity renderEntity = mc.getRenderViewEntity();
        if (renderEntity == null || active_pings.isEmpty()) return;
        double interpX = renderEntity.prevPosX + (renderEntity.func_226277_ct_() - renderEntity.prevPosX) * event.getPartialTicks();
        double interpY = (renderEntity.prevPosY + (renderEntity.func_226278_cu_() - renderEntity.prevPosY) * event.getPartialTicks()) + 1;
        double interpZ = renderEntity.prevPosZ + (renderEntity.func_226281_cx_() - renderEntity.prevPosZ) * event.getPartialTicks();
        ActiveRenderInfo renderInfo = TileEntityRendererDispatcher.instance.renderInfo;

        MatrixStack projectionLook = new MatrixStack();
        EntityViewRenderEvent.CameraSetup cameraSetup = ForgeHooksClient.onCameraSetup(mc.gameRenderer, renderInfo, event.getPartialTicks());
        renderInfo.setAnglesInternal(cameraSetup.getYaw(), cameraSetup.getPitch());
        projectionLook.func_227863_a_(Vector3f.field_229183_f_.func_229187_a_(cameraSetup.getRoll()));
        projectionLook.func_227863_a_(Vector3f.field_229179_b_.func_229187_a_(renderInfo.getPitch()));
        projectionLook.func_227863_a_(Vector3f.field_229181_d_.func_229187_a_(renderInfo.getYaw() + 180.0F));

        MatrixStack entityLocation = new MatrixStack();
        entityLocation.func_227866_c_().func_227870_a_().func_226595_a_(mc.gameRenderer.func_228382_a_(renderInfo, event.getPartialTicks(), true));

        ClippingHelperImpl clippingHelper = new ClippingHelperImpl(projectionLook.func_227866_c_().func_227870_a_(), entityLocation.func_227866_c_().func_227870_a_());
        clippingHelper.func_228952_a_(interpX, interpY, interpZ);

        for (PingWrapper ping : active_pings) {
            double px = ping.pos.getX() + 0.5D - interpX;
            double py = ping.pos.getY() + 0.5D - interpY + 1 - renderEntity.getEyeHeight();
            double pz = ping.pos.getZ() + 0.5D - interpZ;

            if (clippingHelper.func_228957_a_(ping.getAABB())) {
                ping.isOffscreen = false;
                if (Config.VISUAL.blockOverlay.get()) {
                    Vec3d staticPos = TileEntityRendererDispatcher.instance.renderInfo.getProjectedView();
                    renderPingOverlay(ping.pos.getX() - staticPos.getX(), ping.pos.getY() - staticPos.getY(), ping.pos.getZ() - staticPos.getZ(), event.getMatrixStack(), ping);
                }
                renderPing(px, py, pz, event.getMatrixStack(), renderEntity, ping);
            } else {
                ping.isOffscreen = true;
                matrixStack = event.getMatrixStack();
                translatePingCoordinates(px, py, pz, ping);
            }
        }
    }

    @SubscribeEvent
    public static void renderPingOffscreen(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getType() == RenderGameOverlayEvent.ElementType.TEXT) {
            for (PingWrapper ping : active_pings) {
                if (!ping.isOffscreen) {
                    continue;
                }
                int width = mc.func_228018_at_().getWidth();
                int height = mc.func_228018_at_().getHeight();

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

                MatrixStack matrixStack = PingHandler.matrixStack;
                matrixStack.func_227860_a_();
                MatrixStack.Entry matrixEntry = matrixStack.func_227866_c_();
                Matrix4f matrix4f = matrixEntry.func_227870_a_();
                Matrix3f matrix3f = matrixEntry.func_227872_b_();
                Tessellator tessellator = Tessellator.getInstance();
                BufferBuilder bufferBuilder = tessellator.getBuffer();
                IRenderTypeBuffer renderTypeBuffer = IRenderTypeBuffer.func_228455_a_(bufferBuilder);
                IVertexBuilder vertexBuilder = renderTypeBuffer.getBuffer(PING_RENDER);
                Minecraft.getInstance().textureManager.bindTexture(TEXTURE);

                matrixStack.func_227861_a_(pingX / 2, pingY / 2, 0);

                float min = -8;
                float max = 8;

                // Ping Notice Background
                int r = ping.color >> 16 & 255;
                int g = ping.color >> 8 & 255;
                int b = ping.color & 255;
                VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, min, max, PingType.BACKGROUND.minU, PingType.BACKGROUND.maxV, r, g, b, 255);
                VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, max, max, PingType.BACKGROUND.maxU, PingType.BACKGROUND.maxV, r, g, b, 255);
                VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, max, min, PingType.BACKGROUND.maxU, PingType.BACKGROUND.minV, r, g, b, 255);
                VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, min, min, PingType.BACKGROUND.minU, PingType.BACKGROUND.minV, r, g, b, 255);

                // Ping Notice Icon
                int alpha = ping.type == PingType.ALERT ? mc.world != null ? (int) (1.3F + Math.sin(mc.world.getDayTime())) : (int) 1.0F : (int) 1.0F;
                VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, min, max, ping.type.minU, ping.type.maxV, 1.0F, 1.0F, 1.0F, alpha);
                VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, max, max, ping.type.maxU, ping.type.maxV, 1.0F, 1.0F, 1.0F, alpha);
                VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, max, min, ping.type.maxU, ping.type.minV, 1.0F, 1.0F, 1.0F, alpha);
                VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, min, min, ping.type.minU, ping.type.minV, 1.0F, 1.0F, 1.0F, alpha);
                bufferBuilder.finishDrawing();
                WorldVertexBufferUploader.draw(bufferBuilder);

                matrixStack.func_227861_a_(0, 0, 0);

                matrixStack.func_227865_b_();
            }
        }
    }


    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Iterator<PingWrapper> iterator = active_pings.iterator();
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

    private static void renderPing(double px, double py, double pz, MatrixStack matrixStack, Entity renderEntity, PingWrapper ping) {
        Minecraft mc = Minecraft.getInstance();
        matrixStack.func_227860_a_(); //push
        matrixStack.func_227861_a_(px, py, pz); //translate
        //System.out.println("X:" + px + " Y:" + py + " Z:" + pz);
        matrixStack.func_227863_a_(Vector3f.field_229181_d_.func_229187_a_(-renderEntity.rotationYaw)); //rotate
        matrixStack.func_227863_a_(Vector3f.field_229179_b_.func_229187_a_(renderEntity.rotationPitch)); //rotate
        matrixStack.func_227863_a_(Vector3f.field_229183_f_.func_229187_a_(180.0F)); //rotate

        MatrixStack.Entry matrixEntry = matrixStack.func_227866_c_();
        Matrix4f matrix4f = matrixEntry.func_227870_a_();
        Matrix3f matrix3f = matrixEntry.func_227872_b_();
        Tessellator tessellator = Tessellator.getInstance();
        IRenderTypeBuffer renderTypeBuffer = IRenderTypeBuffer.func_228455_a_(tessellator.getBuffer());
        IVertexBuilder vertexBuilder = renderTypeBuffer.getBuffer(PING_RENDER);

        float min = -0.25F - (0.25F * (float) ping.animationTimer / 20F);
        float max = 0.25F + (0.25F * (float) ping.animationTimer / 20F);

        // Block Overlay Background
        int r = ping.color >> 16 & 255;
        int g = ping.color >> 8 & 255;
        int b = ping.color & 255;
        VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, min, max, PingType.BACKGROUND.minU, PingType.BACKGROUND.maxV, r, g, b, 255);
        VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, max, max, PingType.BACKGROUND.minU, PingType.BACKGROUND.maxV, r, g, b, 255);
        VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, max, min, PingType.BACKGROUND.minU, PingType.BACKGROUND.maxV, r, g, b, 255);
        VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, min, min, PingType.BACKGROUND.minU, PingType.BACKGROUND.maxV, r, g, b, 255);

        // Block Overlay Icon
        int alpha = ping.type == PingType.ALERT ? mc.world != null ? (int) (1.3F + Math.sin(mc.world.getDayTime())) : 175 : 175;
        VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, min, max, ping.type.minU, ping.type.maxV, 1.0F, 1.0F, 1.0F, alpha);
        VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, max, max, ping.type.minU, ping.type.maxV, 1.0F, 1.0F, 1.0F, alpha);
        VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, max, min, ping.type.minU, ping.type.maxV, 1.0F, 1.0F, 1.0F, alpha);
        VertexHelper.renderPosTexColor(vertexBuilder, matrix4f, matrix3f, min, min, ping.type.minU, ping.type.maxV, 1.0F, 1.0F, 1.0F, alpha);
        tessellator.draw();

        matrixStack.func_227865_b_(); //pop
    }

    private static void renderPingOverlay(double x, double y, double z, MatrixStack matrixStack, PingWrapper ping) {
        float padding = 0F + (0.20F * (float) ping.animationTimer / (float) 20);
        float box = 1 + padding + padding;

        matrixStack.func_227860_a_();
        matrixStack.func_227861_a_(x + 0.5, y + 0.5, z + 0.5);
        PingRenderHelper.drawBlockOverlay(box, box, box, matrixStack, ping.color, 175);
        matrixStack.func_227861_a_(0, 0, 0);

        matrixStack.func_227865_b_();
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