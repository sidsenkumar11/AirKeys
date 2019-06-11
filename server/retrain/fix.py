import os
from shutil import copyfile

# Function that converts between 'a', 'b', 'c', etc. to label value (a number)
# Quick Conversion Notes:
(36, 'a')
(61, 'z')
(10, 'A')
(35, 'Z')
def fix_label(char):
    if char == 'del':
        return 'del'

    if 48 <= ord(char) <= 57:
        return ord(char) - 48

    if 65 <= ord(char) <= 90:
        return ord(char) - 65 + 10

    if 97 <= ord(char) <= 122:
        return ord(char) - 97 + 36

# Read labels dictionary
with open('labels.txt', 'r') as fin:
    labels = eval(fin.read())

# Fix each label
labels = {k:fix_label(labels[k]) for k in labels}

# Make directory for only the good images
os.mkdir('good_images')

# Move images from 'images' directory to 'good_images' directory
# if the image label isn't 'del', which would indicate the image is useless for training
good_labels = {}
for key in labels:
    if labels[key] == 'del':
        continue
    copyfile(key, key.replace("images", "good_images"))
    good_labels[key.replace('images', 'good_images')] = labels[key]

# Write the good_labels dictionary to disk
with open('good_labels.txt', 'w') as fout:
    fout.write(str(good_labels))
