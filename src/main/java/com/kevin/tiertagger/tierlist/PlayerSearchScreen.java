package com.kevin.tiertagger.tierlist;

import com.kevin.tiertagger.TierCache;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.PlayerSkinWidget;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.ApiServices;
import net.uku3lig.ukulib.config.option.widget.TextInputWidget;
import net.uku3lig.ukulib.config.screen.CloseableScreen;
import net.uku3lig.ukulib.utils.Ukutils;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class PlayerSearchScreen extends CloseableScreen {
    private TextInputWidget textField;
    private ButtonWidget searchButton;

    private boolean searching = false;
    private CompletableFuture<?> future = null;

    public PlayerSearchScreen(Screen parent) {
        super("Player Search", parent);
    }

    @Override
    protected void init() {
        String username = I18n.translate("tiertagger.search.user");
        this.textField = this.addSelectableChild(new TextInputWidget(this.width / 2 - 100, 116, 200, 20,
                "", s -> {
        }, username, s -> s.matches("[a-zA-Z0-9_-]+"), 32));

        this.searchButton = this.addDrawableChild(
                ButtonWidget.builder(Text.translatable("tiertagger.search"), button -> this.loadAndShowProfile())
                        .dimensions(this.width / 2 - 100, this.height / 4 + 96 + 12, 200, 20)
                        .build()
        );

        this.addDrawableChild(
                ButtonWidget.builder(ScreenTexts.CANCEL, button -> {
                            if (this.future != null) {
                                this.future.cancel(true);
                            }
                            this.close();
                        })
                        .dimensions(this.width / 2 - 100, this.height / 4 + 120 + 12, 200, 20)
                        .build()
        );

        this.setInitialFocus(this.textField);
    }

    @Override
    public void tick() {
        super.tick();
        this.searchButton.active = this.textField.isValid() && !searching;
    }

    private void loadAndShowProfile() {
        String username = this.textField.getText();
        this.searching = true;
        this.searchButton.setMessage(Text.translatable("tiertagger.search.loading"));

        ApiServices services = MinecraftClient.getInstance().getApiServices();
        CompletableFuture<PlayerSkinWidget> skinFuture = CompletableFuture.supplyAsync(() -> {
            GameProfile profile = services.profileResolver().getProfileByName(username)
                    .orElseGet(() -> new GameProfile(UUID.randomUUID(), username));

            Supplier<SkinTextures> skinSupplier = MinecraftClient.getInstance().getSkinProvider().supplySkinTextures(profile, true);
            PlayerSkinWidget skin = new PlayerSkinWidget(60, 144, MinecraftClient.getInstance().getLoadedEntityModels(), skinSupplier);
            skin.setPosition(this.width / 2 - 65, (this.height - 144) / 2);
            return skin;
        });

        this.future = TierCache.searchPlayer(username)
                .thenCombine(skinFuture, (info, skin) -> new PlayerInfoScreen(this, info, skin))
                .thenAccept(screen -> MinecraftClient.getInstance().execute(() -> MinecraftClient.getInstance().setScreen(screen)))
                .whenComplete((v, t) -> {
                    if (t != null) {
                        Ukutils.sendToast(Text.translatable("tiertagger.search.unknown"), null);
                    }
                    this.searching = false;
                    this.searchButton.setMessage(Text.translatable("tiertagger.search"));
                });
    }

    @Override
    public void resize(int width, int height) {
        String string = this.textField.getText();
        this.init(width, height);
        this.textField.setText(string);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 20, 16777215);
        this.textField.render(context, mouseX, mouseY, delta);
    }
}
