# Crappy Crocodile (LabDay 2019)

_Crappy Crocodile_ was build as a LabDay project at Netstream AG, to demonstrate a Cloud Native architecture for a [Serverless](https://en.wikipedia.org/wiki/Serverless_computing) video processing application.

## Purpose

Using Google's [Video Intelligence API](https://cloud.google.com/video-intelligence/), detect the objects in a given video, find fitting emojis for these objects and create Emoji subtitles for the video from them.

The Video Intelligence API can detect individual scenes in videos and identify the objects present in each scene.
Using these results, we should then be able to find fitting emojis for the objects in each scene and create a subtitle file for the whole video.

Since the Video Intelligence API currently [only supports](https://cloud.google.com/video-intelligence/docs/reference/rest/v1p3beta1/videos/annotate#request-body) Google Cloud Storage as a source for the videos to analyse, 
our goal will be to build the application within the Google Cloud Platform (GCP) using various serverless features 
(Cloud Storage as an Object Storage, Cloud Pub/Sub as an Event Bus, Cloud Functions for simple serverless Functions, Cloud Run to run Containers in a serverless way).

## Architecture

The application shall use a processing pipeline with a flow where the uploading of a video to an input Bucket triggers the video analysis and initiates the polling of analysis results, new result data in turn triggers the generation of the subtitle files in an output Bucket.

### Components

* Video Preprocessing (Cloud Function): To be triggered by new files in the input Bucket (validates and preprocesses video file, triggers Pub/Sub event)
* Initialization of Analysis (Cloud Run Service): A Container that initiates the video Analysis and schedules the polling of Analysis results (triggered by the Pub/Sub event)
* Checking Analysis Results (Cloud Function): Monitors the Analysis for progress (triggers Pub/Sub events for new results)
* Subtitle Generation (Cloud Run Service): Generates Subtitles files (or ammends existing ones) from new results (triggered by the Pub/Sub event)

### Pipeline

```
     _                 +------------------------+
    (")                |                        |
   \_|_/ Upload Video  |  Google Cloud Storage  |
     |  +------------->+                        |
    / \                |      Input Bucket      |
                       |                        |
                       +-----------+------------+
                                   |
                                   |
                                   | GCS event
                                   |
                                   v
                         +---------+----------+
                         |                    |
                         |   Cloud Function   |  1. Calculate hash
                         |                    |  2. Create Folder in output Bucket an copy video to it
                         |  Preprocess Video  |     (if not exists, use hash as name)
                         |                    |
                         +---------+----------+
                                   |
                                   |
                                   | Pub/Sub Event
                                   |
                                   v
                         +---------+-----------+
                         |                     |
                         |  Cloud Run Service  |            Start Analysis
                +--------+                     +--------------------------+
  Schedule      |        |  Initiate Analysis  |                          |
  in X seconds  |        |                     |                          v
  (using Cloud  |        +---------------------+          +---------------+----------------+
  Scheduler)    |                                         |                                |
                |                                         |  Cloud Video Intelligence API  |
                |                                         |                                |
                |                                         +---------------+----------------+
                v                                                         ^
   +------------+--------------+                                          |
   |                           |             Check Progress / Get Results |
   |       Cloud Function      +<-----------------------------------------+
   |                           |
   |   Check Analysis Results  +<--+
   |                           |   |
   +------------+---------+----+   | Reschedule
                |         |        | in X seconds
                |         +--------+
                |
Pub/Sub Event:  |
Publish Results |        +----------------------+
                |        |                      |  1. Create Subtitles file in video's output Folder
                |        |  Cloud Run Service   |     (if not exists)
                +------->+                      |  2. For each Scene:
                         |  Generate Subtitles  |     + Find fitting Emojis for Objects
                         |                      |     + Ammend Subtitles file
                         +----------+-----------+
                                    |
                                    |
                                    | Generated Subtitles
                                    |
                                    v
                        +-----------+------------+
                        |                        |
                        |  Google Cloud Storage  |
                        |                        |
                        |      Output Bucket     |
                        |                        |
                        +------------------------+

```
(Diagram created using [asciiflow.com](http://asciiflow.com/))

## Usage

### Environment Setup

* To setup the environment for the project, the [Google Cloud SDK](https://cloud.google.com/sdk/) and a GCP Project will be needed.
* To setup your development environment for the Python Cloud Functions, follow the ["Setting up a Python development environment"](https://cloud.google.com/python/setup) guide.

Once the Python environment, the SDK and the Project are set up, one can use the [`./setup.sh`](setup.sh) script to create the required Cloud Storage Buckets.
Either create a configuration file with the name `config.sh` in the same directory to initialize the required environment variables,
or export them manually before starting the setup script.

```
$ cat ./config.sh
#!/bin/bash

export GCP_PROJECT_ID="<GCP-PROJECT-NAME>"
export GCP_LOCATION="europe-west1"

export BUCKET_PREFIX="crappy-croc"

$ ./setup.sh 
Setting GCP project to '<GCP-PROJECT-NAME>'...
Updated property [core/project].
Creating gs://crappy-croc-input/...
Creating gs://crappy-croc-processing/...
Creating gs://crappy-croc-output/...

```

**REMINDER:** GCP Projects and Cloud Storage Buckets need to have a globally unique name, some make sure the names you use are available!



