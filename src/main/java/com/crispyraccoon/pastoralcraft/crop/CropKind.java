package com.crispyraccoon.pastoralcraft.crop;

/**
 * The growth strategy of a crop block as classified by {@link CropKindResolver}.
 *
 * <ul>
 *   <li>{@link #NONE} - not a crop handled by the calendar system.</li>
 *   <li>{@link #AGE} - single-block crop advancing through an age property.</li>
 *   <li>{@link #HEIGHT} - upward-growing stalk tracked by its root block.</li>
 *   <li>{@link #REGROW} - boolean-product crop whose product regrows.</li>
 * </ul>
 */
public enum CropKind {
    NONE,
    AGE,
    HEIGHT,
    REGROW
}
