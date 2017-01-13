package eu.mihosoft.vmf.core;

/**
 * Created by miho on 06.01.2017.
 */
public enum PropType {
    PRIMITIVE,
    CLASS,
    COLLECTION;

    public CollectionType collectionType = CollectionType.NONE;

    public enum CollectionType {
        NONE,
        LIST,
//        SET,
//        MAP
        ;

        public String genericTypeName;
        public String genericPackageName;
    }
}
