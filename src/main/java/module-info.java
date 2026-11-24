module com.studytimer {
    requires javafx.controls;
    requires javafx.media;
    requires java.desktop;
    requires uk.co.caprica.vlcj;
    requires com.fasterxml.jackson.databind;
    requires com.sun.jna;
    requires com.sun.jna.platform;

    exports com.studytimer;
    exports com.studytimer.audio;

    opens com.studytimer.model to com.fasterxml.jackson.databind;
    opens com.studytimer.routine.model to com.fasterxml.jackson.databind;
    opens com.studytimer.routine.service to com.fasterxml.jackson.databind;
    opens com.studytimer.service to com.fasterxml.jackson.databind;
}
