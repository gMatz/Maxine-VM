/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
package com.sun.max.ins.gui;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.table.*;

import com.sun.max.collect.*;
import com.sun.max.ins.*;
import com.sun.max.ins.debug.*;
import com.sun.max.tele.*;


/**
 * A table specialized for use in the Maxine Inspector.
 * <br>
 * Dispatches mouse events to table cell renderers, after first giving
 * implementations an opportunity to respond in the case of left
 * or middle-clicks.  A right click pops up a menu, if provided by
 * an implementation, over the cell where the click took place.
 * <br>
 * After all special handling has completed, the event is passed
 * along to the renderer for the cell where the click took place.
 *
 * @author Michael Van De Vanter
 */
public abstract class InspectorTable extends JTable implements Prober, InspectionHolder {

    public static final int MAXIMUM_ROWS_FOR_COMPUTING_COLUMN_WIDTHS = 100;

    /**
     *   Notification service for table based views that allow columns to be turned on and off.
     */
    public interface ColumnChangeListener {

        /**
         * Notifies that the set of visible columns has been changed.
         */
        void columnPreferenceChanged();
    }

    private final Inspection inspection;

    /**
     * Should the table paint a box around the currently selected row(s)?
     */
    private boolean paintSelectionBox = false;

    /**
     * Creates a new {@JTable} for use in the {@link Inspection}.
     *
     * @param model a model for the table
     * @param tableColumnModel a column model for the table
     */
    protected InspectorTable(Inspection inspection, TableModel model, TableColumnModel tableColumnModel) {
        super(model, tableColumnModel);
        this.inspection = inspection;
        initialize();
        addMouseListener(new InspectorTableMouseListener());
    }

    /**
     * Creates a new {@JTable} for use in the {@link Inspection}.
     */
    protected InspectorTable(Inspection inspection) {
        this.inspection = inspection;
        initialize();
        addMouseListener(new InspectorTableMouseListener());
    }

    private final class InspectorTableMouseListener extends MouseAdapter {
        @Override
        public void mouseClicked(final MouseEvent mouseEvent) {
            final Point p = mouseEvent.getPoint();
            final int col = columnAtPoint(p);
            final int modelCol = getColumnModel().getColumn(col).getModelIndex();
            final int row = rowAtPoint(p);
            //System.out.println("(" + row + "," + col + ")");
            if ((col != -1) && (row != -1)) {
                switch(MaxineInspector.mouseButtonWithModifiers(mouseEvent)) {
                    case MouseEvent.BUTTON1:
                        // Give subclass an opportunity to handle a left-click specially.
                        mouseButton1Clicked(row, modelCol, mouseEvent);
                        break;
                    case MouseEvent.BUTTON2:
                        // Give subclass an opportunity to handle a middle-click specially.
                        mouseButton2Clicked(row, modelCol, mouseEvent);
                        break;
                    case MouseEvent.BUTTON3:
                        // Pop up a menu, if provided by subclass.
                        final InspectorPopupMenu popupMenu = getPopupMenu(row, modelCol, mouseEvent);
                        if (popupMenu != null) {
                            popupMenu.show(mouseEvent.getComponent(), mouseEvent.getX(), mouseEvent.getY());
                        }
                        break;
                }
                // Locate the renderer under the event location and pass along the event.
                final TableCellRenderer tableCellRenderer = getCellRenderer(row, col);
                final Object cellValue = getValueAt(row, col);
                final Component component = tableCellRenderer.getTableCellRendererComponent(InspectorTable.this, cellValue, false, true, row, col);
                if (component != null) {
                    component.dispatchEvent(mouseEvent);
                }
            }
        }
    }

    /**
     * Gives table implementations an opportunity to respond specially to left-button clicks
     * over the table.  Default implementation of this method in null.
     * <br>
     * After this method is called, the left-button click is passed along to the renderer
     * for the cell under the mouse.
     *
     * @param row row in the table model where the click took place
     * @param col column in the column model where the click took place
     * @param mouseEvent the originating event
     */
    protected void mouseButton1Clicked(int row, int col, MouseEvent mouseEvent) {
    }

    /**
     * Gives table implementations an opportunity to respond specially to middle-button clicks
     * over the table.  Default implementation of this method in null.
     * <br>
     * After this method is called, the middle-button click is passed along to the renderer
     * for the cell under the mouse.
     *
     * @param row row in the table model where the click took place
     * @param col column in the column model where the click took place
     * @param mouseEvent the originating event
     */    protected void mouseButton2Clicked(int row, int col, MouseEvent mouseEvent) {
    }

    /**
     * @param row row in the table model where the click took place
     * @param col column in the column model where the click took place
     * @param mouseEvent the originating event
     * @return a popup menu, null if none relevant to location and circumstances.
     */
    protected InspectorPopupMenu getPopupMenu(int row, int col, MouseEvent mouseEvent) {
        return null;
    }

    /**
     * Sets up default view configuration for tables.
     */
    protected void configureDefaultTable(TableModel tableModel, DefaultTableColumnModel columnModel) {
        setModel(tableModel);
        setColumnModel(columnModel);
        setShowHorizontalLines(style().defaultTableShowHorizontalLines());
        setShowVerticalLines(style().defaultTableShowVerticalLines());
        setIntercellSpacing(style().defaultTableIntercellSpacing());
        setRowHeight(style().defaultTableRowHeight());
        setRowSelectionAllowed(true);
        setColumnSelectionAllowed(false);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        refresh(true);
        JTableColumnResizer.adjustColumnPreferredWidths(this, MAXIMUM_ROWS_FOR_COMPUTING_COLUMN_WIDTHS);
        updateFocusSelection();
    }

    /**
     * Sets up standard view configuration for tables used to show memory in one way or another.
     */
    protected void configureMemoryTable(TableModel tableModel, DefaultTableColumnModel columnModel) {
        paintSelectionBox = true;
        setModel(tableModel);
        setColumnModel(columnModel);
        setFillsViewportHeight(true);
        setShowHorizontalLines(style().memoryTableShowHorizontalLines());
        setShowVerticalLines(style().memoryTableShowVerticalLines());
        setIntercellSpacing(style().memoryTableIntercellSpacing());
        setRowHeight(style().memoryTableRowHeight());
        setRowSelectionAllowed(true);
        setColumnSelectionAllowed(false);
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        refresh(true);
        JTableColumnResizer.adjustColumnPreferredWidths(this, MAXIMUM_ROWS_FOR_COMPUTING_COLUMN_WIDTHS);
        updateFocusSelection();
    }


    private void initialize() {
        setOpaque(true);
        setBackground(inspection.style().defaultBackgroundColor());
        getTableHeader().setBackground(inspection.style().defaultBackgroundColor());
        getTableHeader().setFont(style().defaultTextFont());
    }

    /**
     * Updates table state to display a new request for row
     * selection; clears table selection if -1.
     */
    public void updateFocusSelection(int row) {
        if (row < 0) {
            clearSelection();
        } else  if (row != getSelectedRow()) {
            setRowSelectionInterval(row, row);
        }
    }

    @Override
    public void paintChildren(Graphics g) {
        super.paintChildren(g);
        if (paintSelectionBox) {
            // Draw a box around the selected row in the table
            final int row = getSelectedRow();
            if (row >= 0) {
                g.setColor(style().memorySelectedAddressBorderColor());
                g.drawRect(0, row * getRowHeight(row), getWidth() - 1, getRowHeight(row) - 1);
            }
        }
    }

    @Override
    protected JTableHeader createDefaultTableHeader() {
        // Custom table header with tooltips that describe the column data.
        return new JTableHeader(columnModel) {
            @Override
            public String getToolTipText(MouseEvent mouseEvent) {
                final Point p = mouseEvent.getPoint();
                final int index = columnModel.getColumnIndexAtX(p.x);
                final int modelIndex = columnModel.getColumn(index).getModelIndex();
                return WatchpointsColumnKind.VALUES.get(modelIndex).toolTipText();
            }
        };
    }

    public final Inspection inspection() {
        return inspection;
    }

    public final MaxVM maxVM() {
        return inspection.maxVM();
    }

    public final MaxVMState maxVMState() {
        return inspection.maxVM().maxVMState();
    }

    public final InspectorGUI gui() {
        return inspection.gui();
    }

    public final InspectorStyle style() {
        return inspection.style();
    }

    public final InspectionFocus focus() {
        return inspection.focus();
    }

    public InspectionActions actions() {
        return inspection.actions();
    }

    private IdentityHashSet<ColumnChangeListener> columnChangeListeners = new IdentityHashSet<ColumnChangeListener>();

    /**
     * Adds a listener for view update when column visibility changes.
     */
    public void addColumnChangeListener(ColumnChangeListener listener) {
        columnChangeListeners.add(listener);
    }

    /**
     * Remove a listener for view update when column visibility changed.
     */
    public void removeColumnChangeListener(ColumnChangeListener listener) {
        columnChangeListeners.remove(listener);
    }

    /**
     * Notifies listeners that the column visibility preferences for the table have changed.
     */
    public void fireColumnPreferenceChanged() {
        for (ColumnChangeListener listener : columnChangeListeners.clone()) {
            listener.columnPreferenceChanged();
        }
    }

    /**
     * Notifies subclasses that some focus state of interest has changed, typically
     * causing the table's row selection to follow the new focus.
     */
    public void updateFocusSelection() {
    }

    public MaxVMState refresh(boolean force, MaxVMState lastRefreshedState, TableModel tableModel, TableColumn[] columns) {
        MaxVMState maxVMState = maxVMState();
        if (maxVMState.newerThan(lastRefreshedState) || force) {
            for (TableColumn column : columns) {
                final Prober prober = (Prober) column.getCellRenderer();
                if (prober != null) {
                    prober.refresh(force);
                }
            }
        } else {
            maxVMState = lastRefreshedState;
        }
        invalidate();
        repaint();

        return maxVMState;
    }

    public void redisplay(TableColumn[] columns) {
        for (TableColumn column : columns) {
            final Prober prober = (Prober) column.getCellRenderer();
            if (prober != null) {
                prober.redisplay();
            }
        }
        invalidate();
        repaint();
    }

    /**
     * @param listSelectionEvent a change to the current selection in the table
     * @return the newly selected row, null if cannot be determined.
     */
    public Object getChangedValueRow(ListSelectionEvent listSelectionEvent) {
        super.valueChanged(listSelectionEvent);
        if (!listSelectionEvent.getValueIsAdjusting()) {
            final int row = getSelectedRow();
            if (row >= 0) {
                return getValueAt(row, 0);
            }
        }
        return null;
    }

    /**
     * Scrolls the table to display the first row.
     */
    public void scrollToBeginning() {
        scrollToRows(0, 0);
    }

    /**
     * Scrolls the table to display the last row.
     */
    public void scrollToEnd() {
        final int lastRow = getRowCount() - 1;
        scrollToRows(lastRow, lastRow);
    }


    /**
     * Scrolls the table to display the specified range (with a few rows before or after if possible).
     * @param firstRow first row of the range that should be made visible
     * @param lastRow last row of the range that should be made visible
     */
    public void scrollToRows(int firstRow, int lastRow) {
        assert firstRow <= lastRow;
        final int tableWidth = getWidth() - 2;
        final int rowHeight = getRowHeight() - 2;
        // Create a rectangle in the table view to use as a scroll target; include
        // the row immediately before and the row immediately after so that the row of interest
        // doesn't land at the very beginning or very end of the view, if possible.
        final int rowCount = lastRow - firstRow + 1 + 2;
        final Rectangle rectangle = new Rectangle(0, (firstRow - 1) * getRowHeight(), tableWidth, rowCount * rowHeight);
        // System.out.println("row=" + firstRow + " rect=" + rectangle);
        scrollRectToVisible(rectangle);
    }

}
