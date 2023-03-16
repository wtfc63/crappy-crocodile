# Crappy Crocodile (LabDay 2019)

_Crappy Crocodile_ was build as a LabDay project at Netstream AG, to demonstrate a Cloud Native architecture for a [Serverless](https://en.wikipedia.org/wiki/Serverless_computing) video processing application.

You can find a presentation about the project [here](https://docs.google.com/presentation/d/1hnHYhaKOEytpQVy3GxU5EJb5bXH7tRVYb-dAFdQMs8U) \((PDF)[./presentation.pdf]\).

## Purpose

Using Google's [Video Intelligence API](https://cloud.google.com/video-intelligence/), detect the objects in a given video, find fitting emojis for these objects and create Emoji subtitles for the video from them.

The Video Intelligence API can detect individual scenes in videos and identify the objects present in each scene.
Using these results, we should then be able to find fitting emojis for the objects in each scene and create a subtitle file for the whole video.

Since the Video Intelligence API currently [only supports](https://cloud.google.com/video-intelligence/docs/reference/rest/v1p3beta1/videos/annotate#request-body) Google Cloud Storage as a source for the videos to analyse, 
our goal will be to build the application within the Google Cloud Platform (GCP) using various serverless features 
(Cloud Storage as an Object Storage, Cloud Pub/Sub as an Event Bus, Cloud Functions for simple serverless Functions, Cloud Run to run Containers in a serverless way).

## Architecture

The application shall use a processing pipeline with a flow where the uploading of a video to an input Bucket 
triggers the video analysis and the generation of the subtitle files in an processing Bucket.
The finished files are then moved to an output Bucket.

### Components

* Video Preprocessing (Cloud Function): 
  To be triggered by new files in the input Bucket (validates and preprocesses video file, triggers Pub/Sub event)
* Initialization of Analysis (Cloud Run Service): 
  A Container that initiates the video Analysis 
  and ~~schedules the polling of Analysis results~~ generates the Subtitles files (triggered by the Pub/Sub event)

### Pipeline

```
                                      _                 +------------------------+
                                     (")                |                        |
                                    \_+_/ Upload Video  |  Google Cloud Storage  |
                                      +  +------------->+                        |
                                     / \                |      Input Bucket      |
                                                        |                        |
                                                        +-----------+------------+
                                                                    |
                                                                    |
                                                                    | GCS event
                                                                    |
                                                                    |
                                                          +---------+----------+
                                                          |                    |
                                                          |   Cloud Function   |  1. Calculate hash
                                                          |                    |  2. Create Folder in processing Bucket an copy video to it
                                                          |  Preprocess Video  |     (if not exists, use hash as name)
                                                          |                    |  3. Store metadata as JSON in processing Bucket
                                                          +---------+----------+
                                                                    |
                                                                    |
                                                                    | Pub/Sub Event
                                                                    |
                                                                    |
                                                          +---------+-----------+
                                                          |                     |
1. Initiate Analysis through the Video Intelligence API   |  Cloud Run Service  |            Start Analysis
2. Wait for results                                       |                     +--------------------------+
3. Use Shot Lable Annotations to collect a set of Scenes  |  Initiate Analysis  |                          |
4. Generate Subtitles for the Objects in Scene            |                     |                          v
5. Find fitting Emojis for the Objects in Scene and       |                     |          +---------------+----------------+
   create Subtitles for them                              |                     |          |                                |
6. Move the Video, Metadata and Subtitle files            |                     |          |  Cloud Video Intelligence API  |
   to the output Bucket                                   |                     |          |                                |
                                                          |                     |          +---------------+----------------+
                                                          |                     |                          |
                                                          |                     |                          |
                                      +-------------------+                     +<-------------------------+
                                      |                   |                     |
                                      | Generated Files   |                     |
                                      |                   +---------+-----------+
                                      |                             |
                          +-----------+------------+                |
                          |                        |                |
                          |  Google Cloud Storage  |                | Generated Subtitles
                          |                        |                |
                          |    Processing Bucket   |                |
                          |                        |                |
                          +------------------------+     +------------------------+
                                                         |          |             |
                                                         |  Google Cloud Storage  |
                                                         |                        |
                                                         |      Output Bucket     |
                                                         |                        |
                                                         +------------------------+

```
(Diagram created using [asciiflow.com](http://asciiflow.com/))

## Usage

:warning: 
**Since the Video Intelligence API is billed per minute of video processed after the Free Tier is exhaused, 
it is highly recommended to set some pretty low quota on the API during development.
The `init-analysis` Cloud Run service will only create one processing Bucket per video, 
but breaking changes to the service could lead to unacknowledged Pub/Sub messages which might cause a lot of retries.**

### Environment Setup

* To setup the environment for the project, the [Google Cloud SDK](https://cloud.google.com/sdk/) and a GCP Project will be needed.
* To setup your development environment for the Python Cloud Functions, follow the ["Setting up a Python development environment"](https://cloud.google.com/python/setup) guide.
* The [`docker-credential-gcr`](https://github.com/GoogleCloudPlatform/docker-credential-gcr) utility is used to push images to the Google Container Registry during the build process and also needs to be installed.

Once the Python environment, the SDK and the Project are set up, one can use the [`./setup.sh`](setup.sh) script to create the required Cloud Storage Buckets.
Either create a configuration file with the name `config.sh` in the same directory to initialize the required environment variables,
or export them manually before starting the setup script.

```
$ cat ./config.sh
#!/bin/bash
export GCP_PROJECT_ID="<GCP-PROJECT-NAME>"
export GCP_LOCATION="europe-west1"
export GCR_HOST="eu.gcr.io"
export PREFIX="crappy-croc"

$ ./setup.sh 
# Setting GCP project to '<GCP-PROJECT-NAME>' (PROJECT_NUMBER=<GCP-PROJECT-NR>) and default location to 'europe-west1'...
Updated property [core/project].
Updated property [composer/location].
Updated property [run/region].
gcloud credential helpers already registered correctly.
Creating gs://crappy-croc-input/...
Creating gs://crappy-croc-processing/...
Creating gs://crappy-croc-output/...
Created topic [projects/<GCP-PROJECT-NAME>/topics/crappy-croc-file-ready].
Created topic [projects/<GCP-PROJECT-NAME>/topics/crappy-croc-processing-started].
Created topic [projects/<GCP-PROJECT-NAME>/topics/crappy-croc-processing-completed].

# Deployment of Cloud Function 'crappy-croc-process-input'...
Deploying function (may take a while - up to 2 minutes)...done.                                                                                                                                                                                                                          
availableMemoryMb: 128
entryPoint: process_input
environmentVariables:
  PREFIX: crappy-croc
eventTrigger:
  eventType: google.storage.object.finalize
  failurePolicy: {}
  resource: projects/_/buckets/crappy-croc-input
  service: storage.googleapis.com
labels:
  deployment-tool: cli-gcloud
name: projects/<GCP-PROJECT-NAME>/locations/europe-west1/functions/crappy-croc-process-input
runtime: python37
serviceAccountEmail: <GCP-PROJECT-NAME>@appspot.gserviceaccount.com
sourceUploadUrl: https://storage.googleapis.com/gcf-upload-europe-west1-516741e7-c414-4624-9fbe-a37a51f9a81d/8b95de56-ada7-4672-bc0b-f533df71e8ec.zip?GoogleAccessId=service-423718193427@gcf-admin-robot.iam.gserviceaccount.com&Expires=1573492546&Signature=SqKaJTfE0ZFjzHYjNRFkR%2BR5ITIJuFJa6D0zXGIWg5AvgVBu4d9xVMoQx%2FXWVd7D8XaMvCVCao9ZNmA%2B3O2IV5J9sJuQj08NBATjVpgE6XyNB4P%2FUPJxEYg89ztuqu%2Bz0FV6cJeuqWQrENiKxSC4UGASihnrzqpP%2Fc1p%2BVX79MEA0Ez%2FXMGmToccGCng7jCk%2BJUhwAYPD9J5gld83vru31t1IGL4ctriFvIYPxI%2BJC%2FtPp5ts1a2Yh%2Fa0A5q1qA51liJKV0yryQEKFqSe0IjK5YPv6nfoH%2B2Z9E%2FqEmyNvw3OEw77qe%2Fw1qQ792zSuROuhlnm55yFeF0dbhLKqfcUA%3D%3D
status: ACTIVE
timeout: 60s
updateTime: '2019-11-11T16:47:30Z'
versionId: '1'

# Deployment of Cloud Run Service 'crappy-croc-init-analysis'...
Containerizing application to eu.gcr.io/<GCP-PROJECT-NAME>/crappy-croc-init-analysis...
The base image requires auth. Trying again for adoptopenjdk/openjdk11-openj9:alpine-slim...

Container entrypoint set to [java, -XX:TieredStopAtLevel=1, -XX:MaxRAM=256m, -cp, /app/resources:/app/classes:/app/libs/*, com.netstream.ch.lab.crappy_crocodile.init.analysis.Application]

Built and pushed image as eu.gcr.io/<GCP-PROJECT-NAME>/crappy-croc-init-analysis
Executing tasks:
[==============================] 100.0% complete


BUILD SUCCESSFUL in 5s
3 actionable tasks: 1 executed, 2 up-to-date
Deploying container to Cloud Run service [crappy-croc-init-analysis] in project [<GCP-PROJECT-NAME>] region [europe-west1]
✓ Deploying new service... Done.                                                                                                                                                                                                                                                         
  ✓ Creating Revision...                                                                                                                                                                                                                                                                 
  ✓ Routing traffic...                                                                                                                                                                                                                                                                   
  ✓ Setting IAM Policy...                                                                                                                                                                                                                                                                
Done.                                                                                                                                                                                                                                                                                    
Service [crappy-croc-init-analysis] revision [crappy-croc-init-analysis-529w5] has been deployed and is serving 100 percent of traffic at https://crappy-croc-init-analysis-y46c45mdsq-ew.a.run.app
Created subscription [projects/<GCP-PROJECT-NAME>/subscriptions/crappy-croc-init-analysis-subscription].
```

**REMINDER:** GCP Projects and Cloud Storage Buckets need to have a globally unique name, some make sure the names you use are available!

Once the environment is set up, you can upload a video file into the Input Bucket.
Either use the [Google Cloud Console](https://console.cloud.google.com/storage/browser) or the `gsutil` command line client:

```bash
$ gsutil cp sample-videos/JaneGoodall.mp4 gs://crappy-croc-input
```

After a few minutes, a new "folder" should appear in the Output Bucket containing the video file and subtitles.

```bash
$ watch gsutil list gs://crappy-croc-output
Every 2.0s: gsutil list gs://crappy-croc-output                                                                                                                                                                                                 chdhaju0-Desktop: Mon Nov 11 17:03:43 2019

gs://crappy-croc-output/eEM3NmRoZExDMWlxc2ZJdkJsZGpoZz09/
^C

$ gsutil list gs://crappy-croc-output/eEM3NmRoZExDMWlxc2ZJdkJsZGpoZz09/
gs://crappy-croc-output/eEM3NmRoZExDMWlxc2ZJdkJsZGpoZz09/emoji.vtt
gs://crappy-croc-output/eEM3NmRoZExDMWlxc2ZJdkJsZGpoZz09/metadata.json
gs://crappy-croc-output/eEM3NmRoZExDMWlxc2ZJdkJsZGpoZz09/objects.vtt
gs://crappy-croc-output/eEM3NmRoZExDMWlxc2ZJdkJsZGpoZz09/video.mp4
```

To test the generated subtitles, you can download them and use the VideoLAN player:

```bash
$ gsutil cp gs://crappy-croc-output/eEM3NmRoZExDMWlxc2ZJdkJsZGpoZz09/** /tmp/video/
Copying gs://crappy-croc-output/eEM3NmRoZExDMWlxc2ZJdkJsZGpoZz09/emoji.vtt...
Copying gs://crappy-croc-output/eEM3NmRoZExDMWlxc2ZJdkJsZGpoZz09/metadata.json...
Copying gs://crappy-croc-output/eEM3NmRoZExDMWlxc2ZJdkJsZGpoZz09/objects.vtt... 
Copying gs://crappy-croc-output/eEM3NmRoZExDMWlxc2ZJdkJsZGpoZz09/video.mp4...   
\ [4 files][ 29.9 MiB/ 29.9 MiB]                                                
Operation completed over 4 objects/29.9 MiB.

$  vlc --sub-file /tmp/video/objects.vtt /tmp/video/video.mp4
VLC media player 3.0.8 Vetinari (revision 3.0.8-0-gf350b6b5a7)
[...]
$ vlc --sub-file /tmp/video/emoji.vtt /tmp/video/video.mp4
VLC media player 3.0.8 Vetinari (revision 3.0.8-0-gf350b6b5a7)
[...]
```

### Environment Teardown

The [setup script](setup.sh) can also be used to tear down the environment using the `--teardown` parameter:

```bash
$ ./setup.sh --teardown
# Setting GCP project to '<GCP-PROJECT-NAME>' (PROJECT_NUMBER_NR=<GCP-PROJECT-NR>) and default location to 'europe-west1'...
Updated property [core/project].
Updated property [composer/location].
Updated property [run/region].
gcloud credential helpers already registered correctly.

Tearing down the system...
# Removing the Pub/Sub Subscriptions if present...
Deleted subscription [projects/<GCP-PROJECT-NAME>/subscriptions/crappy-croc-init-analysis-subscription].
# Removing the Cloud Run Services if present...
Service [crappy-croc-init-analysis] will be deleted.

Do you want to continue (Y/n)?  y

Deleted service [crappy-croc-init-analysis].
# Removing the Cloud Functions if present...
Resource [projects/<GCP-PROJECT-NAME>/locations/europe-west1/functions/c
rappy-croc-process-input] will be deleted.

Do you want to continue (Y/n)?  y

Waiting for operation to finish...done.                                                                                                                                                                                                                                                  
Deleted [projects/<GCP-PROJECT-NAME>/locations/europe-west1/functions/crappy-croc-process-input].
# Removing the Pub/Sub Topics if present...
Deleted topic [projects/<GCP-PROJECT-NAME>/topics/crappy-croc-file-ready].
Deleted topic [projects/<GCP-PROJECT-NAME>/topics/crappy-croc-processing-started].
Deleted topic [projects/<GCP-PROJECT-NAME>/topics/crappy-croc-processing-completed].
# Removing the Storage Buckets if present...
Removing gs://crappy-croc-input/...
Removing gs://crappy-croc-processing/...
Removing gs://crappy-croc-output/...
```

Keep in mind that there is a 10 day retention period configured for the Output Bucket by default, 
which will prevent you from deleting the Bucket if there are objects in it that are younger than the retention period.
If you want to remove the Bucket earlier, you'll have to delete the Retention Policy on the Bucket 
in order to be able to delete it (can be done in the "Bucket Lock" tab of the [GCP Console](https://console.cloud.google.com/storage/browser/)).
