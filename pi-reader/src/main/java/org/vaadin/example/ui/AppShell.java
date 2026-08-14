package org.vaadin.example.ui;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.server.PWA;

/**
 * A phone opening this on the local network is the point of the whole
 * application, so it is installable to a home screen — and the shell it caches is
 * exactly what the reader wants when the internet is down.
 */
@PWA(name = "ScrewCloud local reader", shortName = "ScrewCloud")
public class AppShell implements AppShellConfigurator {
}
