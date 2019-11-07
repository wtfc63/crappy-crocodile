import base64
import json

from google.cloud import storage


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
            processing_video = move_to_processing(buckets, video)
            video["link"] = processing_video.self_link
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
    source.delete()
    print(f'Moved video "{buckets["input"].name}/{video["name"]}" from input Bucket to processing Bucket '
          f'(new name: "{buckets["processing"].name}/{dest_name}")')
    return dest

def publish(video_info):
    print(f'Publishing "file-ready" event for: {video_info}')
    return json.dumps(video_info)

