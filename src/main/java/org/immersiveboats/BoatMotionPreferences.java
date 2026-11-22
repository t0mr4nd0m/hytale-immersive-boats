package org.immersiveboats;

record BoatMotionPreferences(PlayerPreferences<Boolean> idleRoll, PlayerPreferences<Boolean> rowingPitch,
                             PlayerPreferences<Boolean> showOars) {
}
