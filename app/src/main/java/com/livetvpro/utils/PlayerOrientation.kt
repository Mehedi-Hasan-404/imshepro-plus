package com.livetvpro.utils

enum class PlayerOrientation {
    /**
     * Auto-detects orientation from video aspect ratio.
     * Portrait videos → SENSOR_PORTRAIT
     * Landscape videos → SENSOR_LANDSCAPE
     */
    VIDEO,
    
    /**
     * Allows rotation between landscape orientations only.
     * User can rotate between normal landscape and reverse landscape.
     */
    LANDSCAPE,
    
    /**
     * Allows rotation between portrait orientations only.
     * User can rotate between normal portrait and reverse portrait.
     */
    PORTRAIT,
    
    /**
     * Locks to landscape orientation.
     * No rotation allowed - fixed to normal landscape.
     */
    LOCKED_LANDSCAPE,
    
    /**
     * Locks to portrait orientation.
     * No rotation allowed - fixed to normal portrait.
     */
    LOCKED_PORTRAIT,
    
    /**
     * Follows system auto-rotate setting.
     * Allows free rotation if system auto-rotate is enabled.
     */
    SYSTEM
}
