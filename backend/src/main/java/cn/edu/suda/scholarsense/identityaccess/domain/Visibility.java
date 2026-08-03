package cn.edu.suda.scholarsense.identityaccess.domain;

public enum Visibility {
    CLEAR(0),
    MASKED(1),
    HIDDEN(2);

    private final int restriction;

    Visibility(int restriction) {
        this.restriction = restriction;
    }

    public static Visibility strictest(Visibility left, Visibility right) {
        return left.restriction >= right.restriction ? left : right;
    }
}
