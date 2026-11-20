package org.immersiveboats;

enum LandInteraction {
    BOARD("board"),
    PICK_UP("pick-up"),
    DROP("drop");

    private final String argument;

    LandInteraction(String argument) {
        this.argument = argument;
    }

    String argument() {
        return argument;
    }

    static LandInteraction parse(String argument) {
        for (var interaction : values()) {
            if (interaction.argument.equalsIgnoreCase(argument)) {
                return interaction;
            }
        }
        return null;
    }

    static LandInteraction parseSaved(String argument) {
        return "pick up".equalsIgnoreCase(argument) ? PICK_UP : parse(argument);
    }
}
