module eu.mihosoft.vmf.vmfedit {
        requires javafx.controls;
        requires javafx.fxml;
        requires javafx.web;

        opens eu.mihosoft.vmf.vmfedit to javafx.fxml;
        exports eu.mihosoft.vmf.vmfedit;
}