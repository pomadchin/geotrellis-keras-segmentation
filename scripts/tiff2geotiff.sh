#!/usr/bin/env bash

for entry in 4_Ortho_RGBIR/*
do
  SND=`echo $entry | cut -d \/ -f 2`
  if [[ $SND == *".tif" ]]; then
  	echo $SND
  	gdal_translate -of GTiff 4_Ortho_RGBIR/$SND 4_Ortho_RGBIR_geotiff/$SND
  fi
done

for entry in 3_Ortho_IRRG/*
do
  SND=`echo $entry | cut -d \/ -f 2`
  if [[ $SND == *".tif" ]]; then
  	echo $SND
  	gdal_translate -of GTiff 3_Ortho_IRRG/$SND 3_Ortho_IRRG_geotiff/$SND    
  fi
done

for entry in 5_Labels_for_participants/*
do
  SND=`echo $entry | cut -d \/ -f 2`
  if [[ $SND == *".tif" ]]; then
  	echo $SND
  	gdal_translate -of GTiff -a_srs '+proj=utm +zone=33 +datum=WGS84 +units=m +no_defs ' 5_Labels_for_participants/$SND 5_Labels_for_participants_geotiff/$SND    
  fi
done

for entry in 1_DSM_normalisation/*
do
  SND=`echo $entry | cut -d \/ -f 2`
  if [[ $SND == *"normalized_lastools.jpg" ]]; then
  	echo $SND
  	SND_TIFF=`echo $SND | sed -e 's/.jpg/.tif/g'`
  	gdal_translate -of GTiff -a_srs '+proj=utm +zone=33 +datum=WGS84 +units=m +no_defs ' 1_DSM_normalisation/$SND 1_DSM_normalisation_geotiff/$SND_TIFF
  fi
done
