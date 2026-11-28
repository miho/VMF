package eu.mihosoft.vmf.portable.scalars.vmfmodel;

// Scalar types beyond boolean, int, long, double and String: byte, short, float and char.
interface Scalars {
    byte getTiny();
    short getSmall();
    float getSingle();
    char getLetter();
    Byte getOptionalTiny();
    Float getOptionalSingle();
    Character getOptionalLetter();
    Short[] getSmalls();
    Float[] getSingles();
    Character[] getLetters();
}
