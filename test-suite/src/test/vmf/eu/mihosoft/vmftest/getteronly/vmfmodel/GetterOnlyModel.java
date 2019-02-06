package eu.mihosoft.vmftest.getteronly.vmfmodel;
import eu.mihosoft.vmf.core.*;

@InterfaceOnly
interface WithName {
  @GetterOnly
  String getName();
}

@Immutable
interface ImmutableObj extends WithName {

}

interface MutableObj extends WithName {

}