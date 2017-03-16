#!/usr/bin/env bash

DSM_DATA=1_DSM_normalisation_geotiff
RGBIR_DATA=4_Ortho_RGBIR_geotiff
LABEL_DATA=5_Labels_for_participants_geotiff
LABEL_NB_DATA=5_Labels_for_participants_no_Boundary_geotiff

cd mnt2; uzip '*.zip'

hadoop fs -copyFromLocal /mnt1/${DSM_DATA} ${DSM_DATA}
hadoop fs -copyFromLocal /mnt1/${RGBIR_DATA} ${RGBIR_DATA}
hadoop fs -copyFromLocal /mnt1/${LABEL_DATA} ${LABEL_DATA}
hadoop fs -copyFromLocal /mnt1/${LABEL_NB_DATA} ${LABEL_NB_DATA}
