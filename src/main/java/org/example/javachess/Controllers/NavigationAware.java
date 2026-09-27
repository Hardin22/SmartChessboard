package org.example.javachess.Controllers;

public interface NavigationAware {
    void setMainController(MainController mainController);
    default void onNavigatedFrom() {}
    default void onNavigatedTo() {}
}
