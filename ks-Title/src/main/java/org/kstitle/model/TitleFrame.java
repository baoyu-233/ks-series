package org.kstitle.model;

/** 称号动画帧（同一称号多帧按 frameIndex 顺序循环；单帧=静态称号）。 */
public record TitleFrame(int id, int titleId, int frameIndex, String frameText, int intervalMs) {
}
