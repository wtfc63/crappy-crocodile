import os
import base64
import json

from google.cloud import storage
from google.cloud import pubsub_v1


def process_input(event, context):
    """Triggered by a change to a Cloud Storage bucket.
    Args:
         event (dict): Event payload.
         context (google.cloud.functions.Context): Metadata for the event.
    """
    if event["contentType"].startswith('video/'):
        video = preprocess(event)
        buckets = get_buckets()
        if video_is_not_present(buckets["processing"], video) \
                and video_is_not_present(buckets["output"], video):
            processing = move_to_processing(buckets, video)
            video["url"] = processing["url"]
            video["link"] = processing["blob"].self_link
            store_metadata(buckets["processing"], video)
            return publish(video)
        else:
            msg = f'Video "{video["name"]} was already processed or is currently processing"'
            print(msg)
            buckets["input"].blob(video["name"]).delete()
            return msg
    else:
        raise RuntimeError(
            f'Aborting: "{event["name"]}" is not a video (contentType="{event["contentType"]}")')


def preprocess(video):
    print(f'Preprocessing file: {video["name"]}...')
    print(video)
    video_id = get_video_id(video)
    video_info = {
        'id': video_id,
        'name': video["name"],
        'contentType': video["contentType"],
        'size': video["size"],
        'link': video["selfLink"]
    }
    print(f'Video info: {video_info}')
    return video_info


def get_video_id(file):
    return str(base64.urlsafe_b64encode(file['md5Hash'].encode("utf-8")), "utf-8")


def get_buckets():
    storage_client = storage.Client()
    all_buckets = storage_client.list_buckets()
    buckets = {}
    for bucket in all_buckets:
        if bucket.name.endswith('-input'):
            buckets["input"] = bucket
        elif bucket.name.endswith('-processing'):
            buckets["processing"] = bucket
        elif bucket.name.endswith('-output'):
            buckets["output"] = bucket
    return buckets


def video_is_not_present(bucket, video):
    return not bucket.blob(get_video_blob_name(video)).exists()


def get_video_blob_name(video):
    parts = video["contentType"].split("/")
    extension = parts[len(parts) - 1]
    return f'{video["id"]}/video.{extension}'


def move_to_processing(buckets, video):
    source = buckets["input"].blob(video["name"])
    print(f'Moving "{video["name"]}" ({source})..."')
    dest_name = get_video_blob_name(video)
    dest = buckets["input"].copy_blob(source, buckets["processing"], dest_name)
    dest_url = f'gs://{buckets["processing"].name}/{dest_name}'
    source.delete()
    print(f'Moved video "{buckets["input"].name}/{video["name"]}" from input Bucket to processing Bucket '
          f'(destination-url="{dest_url}")')
    return {'url': dest_url, 'blob': dest}


def store_metadata(bucket, video_info):
    metadata = bucket.blob(f'{video_info["id"]}/metadata.json')
    metadata.upload_from_string(json.dumps(video_info), content_type="application/json")


def publish(video_info):
    publisher = pubsub_v1.PublisherClient()
    data = json.dumps(video_info)
    print(f'Publishing "file-ready" event for: {data}')
    project_id = os.environ.get('GCP_PROJECT', 'GCP_PROJECT is not set!')
    prefix = os.environ.get('PREFIX', 'PREFIX is not set!')
    topic_path = publisher.topic_path(project_id, f'{prefix}-file-ready')
    future = publisher.publish(topic_path, data=data.encode('utf-8'))
    future.add_done_callback(get_callback(future, data))
    return


def get_callback(f, data):
    def callback(f):
        try:
            print(f.result())
        except:  # noqa
            print('Please handle {} for {}.'.format(f.exception(), data))
    return callback
