package eu.mihosoft.vmftest.getteronly.vmfmodel;
import eu.mihosoft.vmf.core.*;


@Immutable
interface MyObjImmutable{
  String getId();
}

@InterfaceOnly
@Immutable
interface MyObj {
  String getId();
}

@InterfaceOnly
interface WithName {
  @GetterOnly
  String getName();
}

@InterfaceOnly
interface WithName2 {
  
  @GetterOnly
  String getName();

  @GetterOnly
  ImmutableObj getMutableObj();
}

@Immutable
interface ImmutableObj extends WithName {
  MyObj getObj();

  WithName2 getWithName();
}

interface MutableObj extends WithName {

}