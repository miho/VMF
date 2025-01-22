module eu.mihosoft.vmf.vmfedit {
        requires javafx.controls;
        requires javafx.fxml;
        requires javafx.web;
        requires jdk.jsobject;
        requires com.fasterxml.jackson.databind;

        opens eu.mihosoft.vmf.vmfedit to javafx.fxml;
        exports eu.mihosoft.vmf.vmfedit;
}