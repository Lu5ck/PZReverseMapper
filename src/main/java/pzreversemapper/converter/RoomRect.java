package pzreversemapper.converter;

/** One rectangle of a RoomDef, in world coordinates or local target-cell coordinates. */
record RoomRect(int sourceRoomId, String name, int floor, int x, int y, int width, int height) {
    int right() {
        return x + width;
    }

    int bottom() {
        return y + height;
    }
}
