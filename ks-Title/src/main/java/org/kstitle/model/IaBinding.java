package org.kstitle.model;

/**
 * 称号 <-> ItemsAdder 图片动画帧 的绑定。真实图片帧文件由管理员在 ItemsAdder 里手动准备
 * （文件名约定 {@code <imagePrefix>_f1..fN} + {@code <imagePrefix>_static}），本插件只负责
 * 记住绑定关系并自动往 TAB 的 config.yml/animations.yml 写入/校验对应占位符接线。
 */
public record IaBinding(int titleId, String imagePrefix, int frameCount, int intervalMs, boolean chatStatic, long createdAt) {
}
