package com.confia.imports.application;

import java.util.List;

/**
 * The answer of a simulation or an application: the batch, and up to {@link ManageImports#MAX_REPORTED} rejections
 * (defects first, then the rows skipped as already imported, each in line order). {@code rejectionsTruncated} says
 * there were more than were returned. The rejections are returned and never stored.
 */
public record ImportRun(ImportBatchView batch, List<RejectionView> rejections, boolean rejectionsTruncated) {

    public ImportRun {
        rejections = List.copyOf(rejections);
    }
}
